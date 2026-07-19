import mill._
import scalalib._
import $file.`rocket-chip`.dependencies.hardfloat.{common => hardfloatCommon}
import $file.`rocket-chip`.dependencies.cde.{common => cdeCommon}
import $file.`rocket-chip`.dependencies.diplomacy.{common => diplomacyCommon}
import $file.`rocket-chip`.{common => rocketChipCommon}

val chiselVersion = "7.0.0-M2"
val defaultScalaVersion = "2.13.14"
val pwd = os.Path(sys.env("MILL_WORKSPACE_ROOT"))

object v {
  def chiselIvy: Option[Dep] = Some(ivy"org.chipsalliance::chisel:${chiselVersion}")
  def chiselPluginIvy: Option[Dep] = Some(ivy"org.chipsalliance:::chisel-plugin:${chiselVersion}")
}

trait HasThisChisel extends SbtModule {
  def chiselModule: Option[ScalaModule] = None
  def chiselPluginJar: T[Option[PathRef]] = None
  def chiselIvy: Option[Dep] = v.chiselIvy
  def chiselPluginIvy: Option[Dep] = v.chiselPluginIvy
  override def scalaVersion = defaultScalaVersion
  override def scalacOptions = super.scalacOptions() ++
    Agg("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")
  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get)
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

object rocketchip extends RocketChip
trait RocketChip extends rocketChipCommon.RocketChipModule with HasThisChisel {
  override def scalaVersion: T[String] = T(defaultScalaVersion)
  override def millSourcePath = pwd / "rocket-chip"
  def dependencyPath = pwd / "rocket-chip" / "dependencies"
  def macrosModule = macros
  def hardfloatModule = hardfloat
  def cdeModule = cde
  def diplomacyModule = diplomacy
  def diplomacyIvy = None
  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.5.4"
  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.6"

  object macros extends Macros
  trait Macros extends rocketChipCommon.MacrosModule with SbtModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}"
  }

  object hardfloat extends Hardfloat
  trait Hardfloat extends hardfloatCommon.HardfloatModule with HasThisChisel {
    override def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "hardfloat" / "hardfloat"
  }

  object cde extends CDE
  trait CDE extends cdeCommon.CDEModule with ScalaModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "cde" / "cde"
  }

  object diplomacy extends Diplomacy
  trait Diplomacy extends diplomacyCommon.DiplomacyModule with ScalaModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "diplomacy" / "diplomacy"

    def chiselModule: Option[ScalaModule] = None
    def chiselPluginJar: T[Option[PathRef]] = None
    def chiselIvy: Option[Dep] = v.chiselIvy
    def chiselPluginIvy: Option[Dep] = v.chiselPluginIvy

    def cdeModule = cde
    def sourcecodeIvy = ivy"com.lihaoyi::sourcecode:0.3.1"
  }
}

trait ysyxSoCModule extends ScalaModule {
  def rocketModule: ScalaModule
  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketModule,
  )
}

object ysyxsoc extends ysyxSoC
trait ysyxSoC extends ysyxSoCModule with HasThisChisel {
  override def millSourcePath = pwd
  // 将 NPC 核心与 FPGA 集成层编入同一个 BSP 目标，使 IDE 能解析 SoC wrapper
  // 对 scpu.NpcCore 及 scpu.fpga 的引用。
  private val npcCoreSourcePath = millSourcePath / os.up / "rv-core" / "main" / "scala"
  private val npcParameterSourcePath = millSourcePath / os.up / "configs" / "parameters"
  private val npcConfigSourcePath = millSourcePath / os.up / "configs" / "npc"
  private val ysyxParameterSourcePath = millSourcePath / os.up / "configs" / "platform"
  private val ysyxConfigSourcePath = millSourcePath / os.up / "configs" / "ysyx"
  private val fpgaConfigSourcePath = millSourcePath / os.up / "configs" / "fpga"
  private val npcFpgaCommonSourcePath = millSourcePath / os.up / "fpga-harness" / "src" / "common"
  private val npcFpgaCoreSourcePath = millSourcePath / os.up / "fpga-harness" / "src" / "rv-core"
  private val npcFpgaSocSourcePath = millSourcePath / os.up / "fpga-harness" / "src" / "ysyxSoC"
  // NPC 核心使用的 DPI BlackBox 从当前模块的 classpath 查找资源，因此这里同时
  // 引入核心资源目录，保证 SoC 生成时仍能选择可选的 DPI 实现。
  private val npcCoreResourcePath = millSourcePath / os.up / "rv-core" / "main" / "resources"
  private val npcConfigResourcePath = millSourcePath / os.up / "configs" / "resources"
  override def sources = Task.Sources(
    millSourcePath / "src",
    npcCoreSourcePath,
    npcParameterSourcePath,
    npcConfigSourcePath,
    ysyxParameterSourcePath,
    ysyxConfigSourcePath,
    fpgaConfigSourcePath,
    npcFpgaCommonSourcePath,
    npcFpgaCoreSourcePath,
    npcFpgaSocSourcePath
  )
  override def resources = Task.Sources(
    millSourcePath / "src" / "main" / "resources",
    npcCoreResourcePath,
    npcConfigResourcePath
  )
  def rocketModule = rocketchip
}
