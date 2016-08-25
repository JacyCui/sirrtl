package firrtl.passes

import firrtl.ir._
import firrtl._
import net.jcazevedo.moultingyaml._
import net.jcazevedo.moultingyaml.DefaultYamlProtocol._
import AnalysisUtils._
import scala.collection.mutable.ArrayBuffer

object CustomYAMLProtocol extends DefaultYamlProtocol {
  // bottom depends on top
  implicit val dr = yamlFormat4(DimensionRules)
  implicit val md = yamlFormat2(MemDimension)
  implicit val sr = yamlFormat4(SRAMRules)
  implicit val wm = yamlFormat2(WMaskArg)
  implicit val sc = yamlFormat10(SRAMCompiler)
}

case class DimensionRules(
  min: Int,
  // step size
  inc: Int,
  max: Int,
  // these values should not be used, regardless of min,inc,max
  illegal: Option[List[Int]]
){
  def getValid = {
    val range = (min to max by inc).toList
    range.filterNot(illegal.getOrElse(List[Int]()).toSet)
  }
}

case class MemDimension(
  rules: Option[DimensionRules],
  set: Option[List[Int]]
){
  require (
    if(rules == None) set != None else set == None, 
    "Should specify either rules or a list of valid options, but not both"
  )
  def getValid = set.getOrElse(rules.get.getValid).sorted
}

case class SRAMConfig(
  ymux: String = "",
  ybank: String = "",
  width: Int,
  depth: Int,
  xsplit: Int = 1,
  ysplit: Int = 1
){
  // how many duplicate copies of this SRAM are needed
  def num = xsplit * ysplit
  def serialize(pattern: String): String = {
    val fieldMap = getClass.getDeclaredFields.map{f => 
      f.setAccessible(true)
      f.getName -> f.get(this)
    }.toMap

    val fieldDelimiter = """\[.*?\]""".r
    val configOptions = fieldDelimiter.findAllIn(pattern).toList

    configOptions.foldLeft(pattern)((b,a) => {
      // Expects the contents of [] are valid configuration fields (otherwise key match error)
      val fieldVal = {
        try fieldMap(a.substring(1,a.length-1)) 
        catch { case e: Exception => Error("**SRAM config field incorrect**") }
      }
      b.replace(a,fieldVal.toString)
    })
  }
}

// Ex: https://www.ece.cmu.edu/~ece548/hw/hw5/meml80.pdf
case class SRAMRules(
  // column mux parameter (for adjusting aspect ratio)
  ymux: (Int,String),
  // vertical segmentation (banking -- tradeoff performance / area)
  ybank: (Int,String),
  width: MemDimension,
  depth: MemDimension
){
  def getValidWidths = width.getValid
  def getValidDepths = depth.getValid
  def getValidConfig(width: Int, depth: Int): Option[SRAMConfig] = {
    if (getValidWidths.contains(width) && getValidDepths.contains(depth)) 
      Some(SRAMConfig(ymux = ymux._2, ybank = ybank._2, width = width, depth = depth))
    else
      None
  }
  def getValidConfig(m: DefMemory): Option[SRAMConfig] = getValidConfig(bitWidth(m.dataType).intValue,m.depth) 
} 

case class WMaskArg(
  t: String,
  f: String
)

// vendor-specific compilers
case class SRAMCompiler(
    vendor: String,
    node: String,
    // i.e. RF, SRAM, etc.
    memType: String,
    portType: String,
    wMaskArg: Option[WMaskArg],
    // rules for valid SRAM flavors
    rules: Seq[SRAMRules],
    // path to executable 
    path: Option[String],
    // (output) config file path
    configFile: Option[String],
    // config pattern
    configPattern: Option[String],
    // read documentation for details 
    defaultArgs: Option[String]
){
  require(portType == "RW" || portType == "R,W", "Memory must be single port RW or dual port R,W")
  require(
    (configFile != None && configPattern != None && wMaskArg != None) || configFile == None, 
    "Config pattern must be provided with config file"
  ) 
  def ymuxVals = rules.map(_.ymux._1).sortWith(_ < _)
  def ybankVals = rules.map(_.ybank._1).sortWith(_ > _)
  // TODO: verify this default ordering works out
  // optimize search for better FoM (area,power,clk); ymux has more effect
  def defaultSearchOrdering = for (x <- ymuxVals; y <- ybankVals) yield {
    rules.find(r => r.ymux._1 == x && r.ybank._1 == y).get
  }

  private val maskConfigOutputBuffer = new java.io.CharArrayWriter
  private val noMaskConfigOutputBuffer = new java.io.CharArrayWriter

  def append(m: DefMemory) : DefMemory = {
    val validCombos = ArrayBuffer[SRAMConfig]()
    defaultSearchOrdering foreach { r =>
      val config = r.getValidConfig(m)
      if (config != None) validCombos += config.get
    }
    // non empty if successfully found compiler option that supports depth/width
    // TODO: don't just take first option
    val usedConfig = {
      if (validCombos.nonEmpty) validCombos.head
      else getBestAlternative(m)
    }
    if (configPattern != None) {
      val newConfig = usedConfig.serialize(configPattern.get) + "\n"
      val currentBuff = {
        if (containsInfo(m.info,"maskGran")) maskConfigOutputBuffer 
        else noMaskConfigOutputBuffer
      }
      if (!currentBuff.toString.contains(newConfig))
        currentBuff.append(newConfig)
    }
    m.copy(info = appendInfo(m.info,"sramConfig" -> usedConfig))    
  }

  // TODO: Should you really be splitting in 2 if, say, depth is 1 more than allowed? should be thresholded and
  // handled w/ a separate set of registers ?
  // split memory until width, depth achievable via given memory compiler
  private def getInRange(m: SRAMConfig): Seq[SRAMConfig] = {
    val validXRange = ArrayBuffer[SRAMRules]()
    val validYRange = ArrayBuffer[SRAMRules]()
    defaultSearchOrdering foreach { r => 
      if (m.width <= r.getValidWidths.max) validXRange += r
      if (m.depth <= r.getValidDepths.max) validYRange += r
    }

    if (validXRange.isEmpty && validYRange.isEmpty)
      getInRange(SRAMConfig(xsplit = 2*m.xsplit, ysplit = 2*m.ysplit, width = m.width/2,depth = m.depth/2))
    else if (validXRange.isEmpty && validYRange.nonEmpty)
      getInRange(SRAMConfig(xsplit = 2*m.xsplit, ysplit = m.ysplit, width = m.width/2,depth = m.depth)) 
    else if (validXRange.nonEmpty && validYRange.isEmpty)
      getInRange(SRAMConfig(xsplit = m.xsplit, ysplit = 2*m.ysplit, width = m.width,depth = m.depth/2)) 
    else if (validXRange.intersect(validYRange).nonEmpty)
      Seq(m)
    else 
      getInRange(SRAMConfig(xsplit = m.xsplit, ysplit = 2*m.ysplit, width = m.width,depth = m.depth/2)) ++ 
        getInRange(SRAMConfig(xsplit = 2*m.xsplit, ysplit = m.ysplit, width = m.width/2,depth = m.depth))    
  }

  private def getBestAlternative(m: DefMemory): SRAMConfig = {
    val validConfigs = getInRange(SRAMConfig(width = bitWidth(m.dataType).intValue, depth = m.depth))
    val minNum = validConfigs.map(x => x.num).min
    val validMinConfigs = validConfigs.filter(_.num == minNum)
    val validMinConfigsSquareness = validMinConfigs.map(x => math.abs(x.width.toDouble/x.depth - 1) -> x).toMap
    val squarestAspectRatio = validMinConfigsSquareness.map(x => x._1).min
    val validConfig = validMinConfigsSquareness(squarestAspectRatio)
    val validRules = ArrayBuffer[SRAMRules]()
    defaultSearchOrdering foreach { r =>
      if (validConfig.width <= r.getValidWidths.max && validConfig.depth <= r.getValidDepths.max) validRules += r
    }
    // TODO: don't just take first option
    val bestRule = validRules.head
    val memWidth = bestRule.getValidWidths.find(validConfig.width <= _).get
    val memDepth = bestRule.getValidDepths.find(validConfig.depth <= _).get
    bestRule.getValidConfig(width = memWidth, depth = memDepth).get.copy(xsplit = validConfig.xsplit, ysplit = validConfig.ysplit)
  }

  def serialize() = {
    // TODO
  }

}  

// TODO: assumption that you would stick to just SRAMs or just RFs in a design -- is that true?
// Or is this where module-level transforms (rather than circuit-level) make sense?
class YamlFileReader(file: String){
  import CustomYAMLProtocol._
  def parse[A](implicit reader: YamlReader[A]) : Seq[A] = {
    if (new java.io.File(file).exists) {
      val yamlString = scala.io.Source.fromFile(file).getLines.mkString("\n")
      val optionOut = yamlString.parseYamls.map(x => 
        try Some(reader.read(x))
        catch {case e: Exception => None} 
      )
      optionOut.filter(_ != None).map(_.get)
    }
    else Error("Yaml file doesn't exist!")
  }
}

class YamlFileWriter(file: String) {
  import CustomYAMLProtocol._
  val outputBuffer = new java.io.CharArrayWriter
  val separator = "--- \n"
  def append(in: YamlValue) = {
    outputBuffer.append(separator + in.prettyPrint)
  }
  def serialize = {
    val outputFile = new java.io.PrintWriter(file)
    outputFile.write(outputBuffer.toString)
    outputFile.close()
  }
}

class AnnotateValidMemConfigs(reader: Option[YamlFileReader]) extends Pass {

  import CustomYAMLProtocol._

  def name = "Annotate memories with valid split depths, widths, #\'s"

  // TODO: Consider splitting InferRW to analysis + actual optimization pass, in case sp doesn't exist
  // TODO: Don't get first available? 
  case class SRAMCompilerSet(
    sp: Option[SRAMCompiler] = None, 
    dp: Option[SRAMCompiler] = None 
  ){
    def serialize() = {
      if (sp != None) sp.get.serialize()
      if (dp != None) dp.get.serialize()
    }
  }
  val sramCompilers = {
    if (reader == None) None
    else {
      val compilers = reader.get.parse[SRAMCompiler] 
      val sp = compilers.find(_.portType == "RW")
      val dp = compilers.find(_.portType == "R,W")
      Some(SRAMCompilerSet(sp = sp, dp = dp))
    }
  }

  def run(c: Circuit) = {
    def annotateModMems(m: Module) = {
      def updateStmts(s: Statement): Statement = s match {
        case m: DefMemory if containsInfo(m.info,"useMacro") => {
          if (sramCompilers == None) m 
          else {
            if (m.readwriters.length == 1)
              if (sramCompilers.get.sp == None) Error("Design needs RW port memory compiler!")
              else sramCompilers.get.sp.get.append(m)
            else
              if (sramCompilers.get.dp == None) Error("Design needs R,W port memory compiler!")
              else sramCompilers.get.dp.get.append(m)
          }
        }  
        case b: Block => Block(b.stmts map updateStmts)
        case s => s
      }
      m.copy(body=updateStmts(m.body))
    } 
    val updatedMods = c.modules map {
      case m: Module => annotateModMems(m)
      case m: ExtModule => m
    }
    c.copy(modules = updatedMods)
  }  
}