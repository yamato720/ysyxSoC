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

/** RTL 基础合同模块：只拥有可被 NPC、SPMV 和 SoC 复用的接口与基础 IP。 */
trait RtlFoundationModule extends HasThisChisel {
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / os.up / "iprouter" / "scala"
  )
  override def resources = Task.Sources(
    pwd / os.up / "iprouter" / "resources"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip)
}

/** NPC RTL 与 NPC 参数合同的独立构建边界。 */
trait RtlNpcModule extends HasThisChisel {
  def foundationModule: ScalaModule
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / os.up / "rv-core" / "scala",
    pwd / os.up / "configs" / "common",
    pwd / os.up / "configs" / "npc",
    pwd / os.up / "configs" / "parameters"
  )
  override def resources = Task.Sources(
    pwd / os.up / "configs" / "resources"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(foundationModule, rocketchip)
}

/** SPMV RTL 与 accelerator 参数合同的独立构建边界。 */
trait RtlSpmvModule extends HasThisChisel {
  def foundationModule: ScalaModule
  def npcModule: ScalaModule
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / os.up / "accelerators" / "common" / "scala",
    pwd / os.up / "accelerators" / "spmv" / "scala",
    pwd / os.up / "accelerators" / "spmv" / "mad-hispmv" / "scala",
    pwd / os.up / "accelerators" / "spmv" / "hispmv" / "scala",
    pwd / os.up / "accelerators" / "spmv" / "cuperflow" / "scala",
    pwd / os.up / "configs" / "accelerators" / "spmv"
  )
  override def resources = Task.Sources(
    pwd / os.up / "configs" / "resources"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(foundationModule, npcModule, rocketchip)
}

/** PCG 产品独立拥有 RTL 和参数，不依赖 SPMV 产品实现。 */
object rtlPcg extends HasThisChisel {
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / os.up / "accelerators" / "pcg-accelerator" / "scala",
    pwd / os.up / "configs" / "accelerators" / "pcg"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(rtlFoundation, rtlNpc, rocketchip)
}

/** SoC 聚合边界向下连接 NPC、SPMV、PCG 和基础合同。 */
trait RtlSocModule extends HasThisChisel {
  def foundationModule: ScalaModule
  def npcModule: ScalaModule
  def spmvModule: ScalaModule
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / "src",
    pwd / os.up / "configs" / "ysyx"
  )
  override def resources = Task.Sources(
    pwd / os.up / "configs" / "resources"
  )
  override def moduleDeps = super.moduleDeps ++
    Seq(foundationModule, npcModule, spmvModule, rtlPcg, rocketchip)
}

/** RTL 到后端的稳定接口，不携带 Vivado、Verilator 或 ASIC 工具实现。 */
trait TargetContractModule extends HasThisChisel {
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / "target-contract" / "scala"
  )
}

/** Verilator、FPGA 和 ASIC 共享的后端模块入口。 */
trait TargetBackendModule extends HasThisChisel {
  def rtlModule: ScalaModule
  def targetContractModule: ScalaModule
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / "target-backends" / "scala"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(rtlModule, targetContractModule)
}

/** Verilator 后端只提供通用 SoC elaborator，不携带 FPGA shell。 */
trait VerilatorTargetModule extends TargetBackendModule {
  override def sources = Task.Sources(
    pwd / "target-backends" / "verilator" / "scala",
    pwd / os.up / "configs" / "fpga" / "CdeConfigResolver.scala",
    pwd / os.up / "configs" / "fpga" / "base",
    pwd / os.up / "configs" / "common" / "core" / "FpgaToolchainConfig.scala",
    pwd / os.up / os.up / "fpga" / "common" / "scala" / "fpga" / "FpgaPlatformSettings.scala"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(rtlModule)
}

/** FPGA 后端还拥有板卡 shell 与工具链 Config，仍通过统一 target contract 接入。 */
trait FpgaTargetModule extends TargetBackendModule {
  override def sources = Task.Sources(
    pwd / os.up / os.up / "fpga" / "common" / "scala",
    pwd / os.up / os.up / "fpga" / "u55c" / "scala",
    pwd / os.up / os.up / "fpga" / "zcu102" / "scala",
    pwd / os.up / "configs" / "fpga"
  )
  override def resources = Task.Sources(
    pwd / os.up / "configs" / "resources"
  )
}

/** 构造描述器和 profile writer，独立于 RTL 设计本身。 */
trait ConstructionToolsModule extends HasThisChisel {
  def rtlModule: ScalaModule
  def fpgaModule: ScalaModule
  override def millSourcePath = pwd
  override def sources = Task.Sources(
    pwd / "construction-tools" / "scala"
  )
  override def moduleDeps = super.moduleDeps ++ Seq(rtlModule, fpgaModule, rocketchip)
}

object rtlFoundation extends RtlFoundationModule
object rtlNpc extends RtlNpcModule {
  def foundationModule = rtlFoundation
}
object rtlSpmv extends RtlSpmvModule {
  def foundationModule = rtlFoundation
  def npcModule = rtlNpc
}
object rtlSoc extends RtlSocModule {
  def foundationModule = rtlFoundation
  def npcModule = rtlNpc
  def spmvModule = rtlSpmv
}
object targetContract extends TargetContractModule
object targetVerilator extends VerilatorTargetModule {
  def rtlModule = rtlSoc
  def targetContractModule = targetContract
}
object targetFpga extends FpgaTargetModule {
  def rtlModule = rtlSoc
  def targetContractModule = targetContract
}
object targetAsic extends TargetBackendModule {
  override def sources = Task.Sources(
    pwd / "target-backends" / "asic" / "scala",
    pwd / os.up / "configs" / "asic"
  )
  override def resources = Task.Sources(
    pwd / os.up / "configs" / "resources"
  )
  def rtlModule = rtlSoc
  def targetContractModule = targetContract
}
object constructionTools extends ConstructionToolsModule {
  def rtlModule = rtlSoc
  def fpgaModule = targetFpga
  override def moduleDeps = super.moduleDeps ++ Seq(targetAsic)
}

object ysyxsoc extends ysyxSoC
trait ysyxSoC extends ysyxSoCModule with HasThisChisel {
  override def millSourcePath = pwd
  // 聚合入口只连接分层模块；各模块拥有自己的源码和资源边界。
  override def sources = Task.Sources()
  override def resources = Task.Sources()
  // ASIC 与 FPGA 在 Scala 聚合入口中并列；具体 Yosys 工具由 npc/asic 侧 runner 调用。
  override def moduleDeps = super.moduleDeps ++
    Seq(rtlSoc, targetVerilator, targetFpga, targetAsic, constructionTools)
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
  private val spmvCuperflowTestRoot = pwd / os.up / "accelerators" / "spmv" / "cuperflow" / "test"
  private val spmvCuperflowE1TestPath = spmvCuperflowTestRoot / "input-mul"
  private val spmvRowfoldInputTestPath = pwd / os.up / "accelerators" / "spmv" / "test" /
    "input-mul" / "rowfold"
  private val spmvCuperflowE2TestPath = spmvCuperflowTestRoot / "l1"
  private val spmvCuperflowE3TestPath = spmvCuperflowTestRoot / "l2"
  private val spmvCuperflowL2TreeTestPath = spmvCuperflowTestRoot / "l2" / "tree"
  private val spmvCuperflowE4TestPath = spmvCuperflowTestRoot / "pipeline"
  private val spmvConfigTestPath = pwd / os.up / "accelerators" / "spmv" / "test" / "config"
  private val spmvMadConfigTestPath = pwd / os.up / "accelerators" / "spmv" / "mad-hispmv" / "test" / "config"
  // E1 ingress 是与现有 FPGA Config contract 同一 RTL 编译边界的一部分。显式列出
  // E1/E2 的 ingress 与 epoch/result-slot 合同都处于同一 RTL 编译边界。显式列出
  // 显式列出 E1--E4 Verilator contract test，避免把整个历史 SPMV test 目录意外变成 SoC 回归范围。
  override def sources = Task.Sources(
    millSourcePath / "FpgaConfigCompositionTest.scala",
    millSourcePath / "SpmvMadHiSpmvFpgaConfigTest.scala",
    spmvCuperflowE1TestPath / "SpmvCuperflowL1IngressPackerTest.scala",
    spmvCuperflowE1TestPath / "SpmvCuperflowCompactIngressBridgeTest.scala",
    spmvRowfoldInputTestPath / "SpmvRowfoldInputTopTest.scala",
    spmvCuperflowE2TestPath / "SpmvCuperflowL1Test.scala",
    spmvCuperflowE2TestPath / "SpmvCuperflowEpochIngressTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochBarrierStageTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochBarrierChainTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochIngressBarrierTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochIngressBarrierScanTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochIngressRendezvousTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochRendezvousChainTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochIngressTaggedChainTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowTaggedRightwardElasticLinkTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowEpochRendezvousErrorTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowRendezvousIi1Test.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowFoldRoleRendezvousTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowL2LaneConfigTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowTaggedDualTransportLaneTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowTaggedTerminalCompatTest.scala",
    spmvCuperflowE3TestPath / "SpmvCuperflowTaggedHeaderAdmissionTest.scala",
    spmvCuperflowL2TreeTestPath / "SpmvRowfoldL2Tree16Test.scala",
    spmvCuperflowE4TestPath / "SpmvCuperflowEpochPipeline16PcTopTest.scala",
    spmvCuperflowE4TestPath / "SpmvCuperflowE4LocalMetadataBridgeTest.scala",
    spmvMadConfigTestPath / "SpmvMadHiSpmvTapaConfigTest.scala",
    pwd / os.up / "accelerators" / "pcg-accelerator" / "test"
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
