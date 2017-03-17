// See LICENSE for license details.

package firrtl.passes

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.Mappers._

/** Removes all aggregate types from a [[firrtl.ir.Circuit]]
  *
  * @note Assumes [[firrtl.ir.SubAccess]]es have been removed
  * @note Assumes [[firrtl.ir.Connect]]s and [[firrtl.ir.IsInvalid]]s only operate on [[firrtl.ir.Expression]]s of ground type
  * @example
  * {{{
  *   wire foo : { a : UInt<32>, b : UInt<16> }
  * }}} lowers to
  * {{{
  *   wire foo_a : UInt<32>
  *   wire foo_b : UInt<16>
  * }}}
  */
object LowerTypes extends Pass {
  def name = "Lower Types"

  /** Delimiter used in lowering names */
  val delim = "_"
  /** Expands a chain of referential [[firrtl.ir.Expression]]s into the equivalent lowered name
    * @param e [[firrtl.ir.Expression]] made up of _only_ [[firrtl.WRef]], [[firrtl.WSubField]], and [[firrtl.WSubIndex]]
    * @return Lowered name of e
    */
  def loweredName(e: Expression): String = e match {
    case e: WRef => e.name
    case e: WSubField => s"${loweredName(e.exp)}$delim${e.name}"
    case e: WSubIndex => s"${loweredName(e.exp)}$delim${e.value}"
  }
  def loweredName(s: Seq[String]): String = s mkString delim

  private case class LowerTypesException(msg: String) extends FIRRTLException(msg)
  private def error(msg: String)(info: Info, mname: String) =
    throw LowerTypesException(s"$info: [module $mname] $msg")

  // TODO Improve? Probably not the best way to do this
  private def splitMemRef(e1: Expression): (WRef, WRef, WRef, Option[Expression]) = {
    val (mem, tail1) = splitRef(e1)
    val (port, tail2) = splitRef(tail1)
    tail2 match {
      case e2: WRef =>
        (mem, port, e2, None)
      case _ =>
        val (field, tail3) = splitRef(tail2)
        (mem, port, field, Some(tail3))
    }
  }

  // Lowers an expression of MemKind
  // Since mems with Bundle type must be split into multiple ground type
  //   mem, references to fields addr, en, clk, and rmode must be replicated
  //   for each resulting memory
  // References to data, mask, rdata, wdata, and wmask have already been split in expand connects
  //   and just need to be converted to refer to the correct new memory
  type MemDataTypeMap = collection.mutable.HashMap[String, Type]
  def lowerTypesMemExp(memDataTypeMap: MemDataTypeMap,
      info: Info, mname: String)(e: Expression): Seq[Expression] = {
    val (mem, port, field, tail) = splitMemRef(e)
    field.name match {
      // Fields that need to be replicated for each resulting mem
      case "addr" | "en" | "clk" | "wmode" =>
        require(tail.isEmpty) // there can't be a tail for these
        memDataTypeMap(mem.name) match {
          case _: GroundType => Seq(e)
          case memType => create_exps(mem.name, memType) map { e =>
            val loMemName = loweredName(e)
            val loMem = WRef(loMemName, UnknownType, UnknownLabel, kind(mem), UNKNOWNGENDER)
            mergeRef(loMem, mergeRef(port, field))
          }
        }
      // Fields that need not be replicated for each
      // eg. mem.reader.data[0].a
      // (Connect/IsInvalid must already have been split to ground types)
      case "data" | "mask" | "rdata" | "wdata" | "wmask" =>
        val loMem = tail match {
          case Some(ex) =>
            val loMemExp = mergeRef(mem, ex)
            val loMemName = loweredName(loMemExp)
            WRef(loMemName, UnknownType, UnknownLabel, kind(mem), UNKNOWNGENDER)
          case None => mem
        }
        Seq(mergeRef(loMem, mergeRef(port, field)))
      case name => error(s"Error! Unhandled memory field $name")(info, mname)
    }
  }

  def lowerTypesExp(memDataTypeMap: MemDataTypeMap,
      info: Info, mname: String)(e: Expression): Expression = e match {
    case e: WRef => e
    case (_: WSubField | _: WSubIndex) => kind(e) match {
      case InstanceKind =>
        val (root, tail) = splitRef(e)
        val name = loweredName(tail)
        WSubField(root, name, e.tpe, e.lbl, gender(e))
      case MemKind =>
        val exps = lowerTypesMemExp(memDataTypeMap, info, mname)(e)
        exps.size match {
          case 1 => exps.head
          case _ => error("Error! lowerTypesExp called on MemKind " + 
                          "SubField that needs to be expanded!")(info, mname)
        }
      case _ => WRef(loweredName(e), e.tpe, e.lbl, kind(e), gender(e))
    }
    case e: Mux => e map lowerTypesExp(memDataTypeMap, info, mname)
    case e: ValidIf => e map lowerTypesExp(memDataTypeMap, info, mname)
    case e: DoPrim => e map lowerTypesExp(memDataTypeMap, info, mname)
    case e @ (_: UIntLiteral | _: SIntLiteral) => e
  }

  def lowerTypesStmt(memDataTypeMap: MemDataTypeMap,
      minfo: Info, mname: String)(s: Statement): Statement = {
    val info = get_info(s) match {case NoInfo => minfo case x => x}
    s map lowerTypesStmt(memDataTypeMap, info, mname) match {
      case s: DefWire => s.tpe match {
        case _: GroundType => s
        case _ => Block(create_exps(s.name, s.tpe) map (
          e => DefWire(s.info, loweredName(e), e.tpe, s.lbl)))
      }
      case sx: DefRegister => sx.tpe match {
        case _: GroundType => sx map lowerTypesExp(memDataTypeMap, info, mname)
        case _ =>
          val es = create_exps(sx.name, sx.tpe)
          val inits = create_exps(sx.init) map lowerTypesExp(memDataTypeMap, info, mname)
          val clock = lowerTypesExp(memDataTypeMap, info, mname)(sx.clock)
          val reset = lowerTypesExp(memDataTypeMap, info, mname)(sx.reset)
          Block(es zip inits map { case (e, i) =>
            DefRegister(sx.info, loweredName(e), e.tpe, sx.lbl, clock, reset, i)
          })
      }
      // Could instead just save the type of each Module as it gets processed
      case sx: WDefInstance => sx.tpe match {
        case t: BundleType =>
          val fieldsx = t.fields flatMap (f =>
            create_exps(WRef(f.name, f.tpe, f.lbl, ExpKind, times(f.flip, MALE))) map (
              // Flip because inst genders are reversed from Module type
              e => Field(loweredName(e), swap(to_flip(gender(e))), e.tpe, f.lbl, f.isSeq)))
          WDefInstance(sx.info, sx.name, sx.module, BundleType(fieldsx), BundleLabel(fieldsx))
        case _ => error("WDefInstance type should be Bundle!")(info, mname)
      }
      case sx: DefMemory =>
        memDataTypeMap(sx.name) = sx.dataType
        sx.dataType match {
          case _: GroundType => sx
          case _ => Block(create_exps(sx.name, sx.dataType) map (e =>
            sx copy (name = loweredName(e), dataType = e.tpe)))
        }
      // wire foo : { a , b }
      // node x = foo
      // node y = x.a
      //  ->
      // node x_a = foo_a
      // node x_b = foo_b
      // node y = x_a
      case sx: DefNode =>
        val names = create_exps(sx.name, sx.value.tpe) map lowerTypesExp(memDataTypeMap, info, mname)
        val exps = create_exps(sx.value) map lowerTypesExp(memDataTypeMap, info, mname)
        Block(names zip exps map { case (n, e) => DefNode(info, loweredName(n), e, sx.lbl) })
      case sx: IsInvalid => kind(sx.expr) match {
        case MemKind =>
          Block(lowerTypesMemExp(memDataTypeMap, info, mname)(sx.expr) map (IsInvalid(info, _)))
        case _ => sx map lowerTypesExp(memDataTypeMap, info, mname)
      }
      case sx: Connect => kind(sx.loc) match {
        case MemKind =>
          val exp = lowerTypesExp(memDataTypeMap, info, mname)(sx.expr)
          val locs = lowerTypesMemExp(memDataTypeMap, info, mname)(sx.loc)
          Block(locs map (Connect(info, _, exp)))
        case _ => sx map lowerTypesExp(memDataTypeMap, info, mname)
      }
      case sx => sx map lowerTypesExp(memDataTypeMap, info, mname)
    }
  }

  def lowerTypes(m: DefModule): DefModule = {
    val memDataTypeMap = new MemDataTypeMap
    // Lower Ports
    val portsx = m.ports flatMap { p =>
      val exps = create_exps(WRef(p.name, p.tpe, p.lbl, PortKind, to_gender(p.direction)))
      exps map (e => Port(p.info, loweredName(e), to_dir(gender(e)), e.tpe, UnknownLabel))
    }
    m match {
      case m: ExtModule =>
        m copy (ports = portsx)
      case m: Module =>
        m copy (ports = portsx) map lowerTypesStmt(memDataTypeMap, m.info, m.name)
    }
  }

  def run(c: Circuit): Circuit = c copy (modules = c.modules map lowerTypes)
}

