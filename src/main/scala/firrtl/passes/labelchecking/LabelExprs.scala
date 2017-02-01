package firrtl.passes

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.Mappers._

// This pass propagates labels from declarations to expressions (e.g. nodes).
object LabelExprs extends Pass with PassDebug {
  def name = "Label Expressions"
  override def debugThisPass = false
  type LabelMap = collection.mutable.LinkedHashMap[String, Label]

  val bot = PolicyHolder.policy.bottom
  val top = PolicyHolder.policy.top

  class UndeclaredException(info: Info, name: String) extends PassException(
    s"$info: [declaration $name] does not have a declared label")
  class UnknownLabelException(info: Info, name: String) extends PassException(
    s"$info: a label could not be inferred for [$name]")
  val errors = new Errors()

  def throwErrors = true

  // Assume that if the label was omitted, that the least-restrictive label was 
  // the desired one. This function should only be used for things like 
  // constants and clocks. This is mostly used to make the places where \bot is
  // assumed very obvious
  def assumeL(l:Label) = if(label_is_known(l)) l else bot

  def label_is_known(l: Label): Boolean = {
    var b = true
    def label_is_known_(l: Label): Label =
      l map label_is_known_ match {
        case UnknownLabel => b = false; UnknownLabel
        case lx => lx
      }
    label_is_known_(l); b
  }

  def checkDeclared(l: Label, i: Info, n: String) = 
    if(!label_is_known(l) && throwErrors)
      errors.append(new UndeclaredException(i, n))
    
  def checkKnown(l: Label, i: Info, n: String) = 
    if(!label_is_known(l) && throwErrors)
      errors.append(new UnknownLabelException(i, n))
    
  // This function is used for declarations with BundleTypes to convert their 
  // labels into BundleLabels
  def to_bundle(t: Type, l: Label) : Label = {
    def to_bundle__(t: Type, l: Label) = t match {
      case BundleType(fields) => BundleLabel(fields)
      case _ => l
    }
    // Recursively convert BundleTypes within a BundleType to BundleLabels
    def to_bundle_(t: Type) : Type = t map to_bundle_ match {
      case tx : BundleType => tx copy (fields = tx.fields map { f =>
        f copy (lbl = to_bundle__(f.tpe, f.lbl))
      })
      case tx => tx
    }
    if(label_is_known(l)) l else to_bundle__(t map to_bundle_, l)
  }

  def label_exprs_e(labels: LabelMap)(e: Expression) : Expression =
    e map label_exprs_e(labels) match {
      case e: WRef => e copy (lbl = labels(e.name))
      case e: Next => e copy (lbl = e.exp.lbl)
      case e: WSubField =>
        e copy (lbl = field_label(e.exp.lbl, e.name))
      case e: WSubIndex => e copy (lbl = e.exp.lbl)
      case e: WSubAccess => e copy (lbl = JoinLabel(e.exp.lbl, e.index.lbl))
      case e: DoPrim => e copy (lbl = JoinLabel((e.args map{ _.lbl }):_* ))
      case e: Mux => e copy (lbl = JoinLabel(e.cond.lbl,
        e.tval.lbl, e.fval.lbl))
      case e: ValidIf => e copy (lbl = JoinLabel(e.cond.lbl, e.value.lbl))
      case e: UIntLiteral => e.copy (lbl = assumeL(e.lbl))
      case e: SIntLiteral => e.copy (lbl = assumeL(e.lbl))
  }

  def label_exprs_s(labels: LabelMap)(s: Statement): Statement = 
    s map label_exprs_s(labels) map label_exprs_e(labels) match {
      case sx: WDefInstance =>
        val lb = to_bundle(sx.tpe, UnknownLabel)
        checkDeclared(lb, sx.info, sx.name)
        labels(sx.name) = lb
        sx copy (lbl = lb)
      case sx: DefWire =>
        val lb = to_bundle(sx.tpe, sx.lbl)
        checkDeclared(lb, sx.info, sx.name)
        labels(sx.name) = lb
        sx copy (lbl = lb)
      case sx: DefRegister =>
        val lb = to_bundle(sx.tpe, sx.lbl)
        checkDeclared(lb, sx.info, sx.name)
        val lbx = JoinLabel(lb, assumeL(sx.clock.lbl),
          assumeL(sx.reset.lbl), sx.init.lbl)
        checkDeclared(lbx, sx.info, sx.name)
        labels(sx.name) = lbx
        val sxx = sx copy (lbl = lbx)
        sxx
      case sx: DefNode =>
        checkKnown(sx.value.lbl, sx.info, sx.name)
        labels(sx.name) = sx.value.lbl
        sx
      // Not sure what should be done for:
      // WDefInstance 
      // DefMemory
      case sx =>  sx
  }

  // Add each port declaration to the label context
  def label_exprs_p(labels: LabelMap)(p: Port) : Port = {
    val lb = to_bundle(p.tpe, p.lbl)
    checkDeclared(lb, p.info, p.name)
    labels(p.name) = lb
    p copy (lbl = lb)
  }

  def label_exprs(m: DefModule) : DefModule = {
    val labels = new LabelMap
    m map label_exprs_p(labels) map label_exprs_s(labels)
  }

  def run(c: Circuit) = {
    bannerprintb(name)
    dprint(c.serialize)

    val cprime = c copy (modules = c.modules map label_exprs)

    bannerprintb(s"after $name")
    dprint(cprime.serialize)
 
    errors.trigger()
    cprime
  }
}
