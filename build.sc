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
  // 对 npc.NpcCore、fpga 共享层及各产品 FPGA 顶层的引用。
  private val npcCoreSourcePath = millSourcePath / os.up / "rv-core" / "scala"
  private val npcIpInterfaceSourcePath = millSourcePath / os.up / "ip-interface" / "scala"
  private val npcConfigSourcePath = millSourcePath / os.up / "configs"
  private val commonAcceleratorSourcePath = millSourcePath / os.up / "accelerators" / "common" / "scala"
  private val spmvAcceleratorSourcePath = millSourcePath / os.up / "accelerators" / "spmv" / "scala"
  private val npcFpgaRootPath = millSourcePath / os.up / os.up / "fpga"
  private val fpgaCommonSourcePath = npcFpgaRootPath / "common" / "scala"
  private val npcFpgaU55cSourcePath = npcFpgaRootPath / "u55c" / "scala"
  private val npcFpgaZcu102SourcePath = npcFpgaRootPath / "zcu102" / "scala"
  // DPI BlackBox 从当前模块的 classpath 查找资源，因此 Mill 直接复用
  // ip-interface 的稳定资源根，不再保留不存在的 rv-core 资源路径。
  private val npcIpResourcePath = millSourcePath / os.up / "ip-interface" / "resources"
  private val npcConfigResourcePath = millSourcePath / os.up / "configs" / "resources"
  override def sources = Task.Sources(
    millSourcePath / "src",
    npcCoreSourcePath,
    npcIpInterfaceSourcePath,
    npcConfigSourcePath,
    commonAcceleratorSourcePath,
    spmvAcceleratorSourcePath,
    fpgaCommonSourcePath,
    npcFpgaU55cSourcePath,
    npcFpgaZcu102SourcePath
  )
  override def resources = Task.Sources(
    npcIpResourcePath,
    npcConfigResourcePath
  )
  def rocketModule = rocketchip
}

/** FPGA Config 与 ysyxSoC 在同一个 Mill 编译边界中检查，避免同一终端 Config 被
  * SBT/Mill 以不同源码过滤规则重复定义。
  */
object ysyxsocTest extends ysyxSoCTest

trait ysyxSoCTest
  extends TestModule
    with HasThisChisel
    with TestModule.ScalaTest {
  override def millSourcePath = pwd / os.up / os.up / "fpga" / "common" / "test"
  private val spmvCuperflowE1TestPath = pwd / os.up / "accelerators" / "spmv" / "test" /
    "input-mul" / "cuperflow"
  private val spmvCuperflowE2TestPath = pwd / os.up / "accelerators" / "spmv" / "test" /
    "l1" / "cuperflow"
  private val spmvCuperflowE3TestPath = pwd / os.up / "accelerators" / "spmv" / "test" /
    "l2" / "cuperflow"
  private val spmvCuperflowE4TestPath = pwd / os.up / "accelerators" / "spmv" / "test" /
    "pipeline" / "cuperflow"
  // E1 ingress 是与现有 FPGA Config contract 同一 RTL 编译边界的一部分。显式列出
  // E1/E2 的 ingress 与 epoch/result-slot 合同都处于同一 RTL 编译边界。显式列出
  // 三份 Verilator contract test，避免把整个历史 SPMV test 目录意外变成 SoC 回归范围。
  override def sources = Task.Sources(
    millSourcePath,
    spmvCuperflowE1TestPath / "SpmvCuperflowL1IngressPackerTest.scala",
    spmvCuperflowE1TestPath / "SpmvCuperflowCompactIngressBridgeTest.scala",
    spmvCuperflowE2TestPath / "SpmvCuperflowEpochIngressTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochBarrierStageTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochBarrierChainTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochIngressBarrierTest.scala",
    spmvCuperflowE4TestPath / "SpmvCuperflowEpochPipeline16PcTopTest.scala"
  )
  def ysyxSoCModule: ScalaModule = ysyxsoc
  def chiselModule: Option[ScalaModule] = None
  def chiselPluginJar: T[Option[PathRef]] = None
  def chiselIvy: Option[Dep] = v.chiselIvy
  def chiselPluginIvy: Option[Dep] = v.chiselPluginIvy
  override def moduleDeps = super.moduleDeps ++ Seq(ysyxSoCModule)
  override def ivyDeps = super.ivyDeps() ++ Agg(ivy"org.scalatest::scalatest:3.2.19")
  override def defaultCommandName() = "test"
}
