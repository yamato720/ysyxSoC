package ysyx

import java.nio.file.Path

import org.chipsalliance.cde.config.{Config => CDEConfig, Parameters}

import _root_.accelerators.spmv.{
  SpmvMadHiSpmvConfig,
  SpmvMadHiSpmvConfigKey,
  SpmvMadHiSpmvFpgaProfile,
  SpmvMadHiSpmvSimulationConstruction,
  SpmvMadHiSpmvSimulationProfile,
  SpmvMadHiSpmvTapaFpgaRuntimeConstruction
}
import _root_.fpga.FpgaConfigParameters
import _root_.npc.{
  AcceleratorHostConstruction,
  CdeConfigResolver,
  ConfigCatalog,
  Construction,
  ConstructionProfile,
  FpgaAssetLayoutConfig,
  FpgaToolchainConstruction,
  MakeTerminal,
  RunnerPlanProvider
}

/** 为强制 build 入口生成稳定的构造 profile。 */
object DescribeBuildPlan extends App {
  require(args.length == 1, "用法：ysyx.DescribeBuildPlan <profile.env>")

  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  private def fpgaPlatformValues(implicit parameters: Parameters): Seq[(String, String)] = {
    val platform = FpgaConfigParameters.platform
    Seq(
      "FPGA_BOARD" -> platform.board.name,
      "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
      "FPGA_PLATFORM_CLOCK_MHZ" -> platform.platformClockMHz.toString,
      "FPGA_MEMORY_HOST_BASE" -> hex(platform.memoryHostBase),
      "FPGA_CONTROL_BASE" -> hex(platform.controlBase),
      "FPGA_MAILBOX_BASE" -> hex(platform.mailboxBase)
    )
  }

  private def checked(values: Seq[(String, String)]): Seq[(String, String)] = {
    val duplicateKeys = values.groupBy(_._1).collect { case (key, entries) if entries.size > 1 => key }
    require(duplicateKeys.isEmpty, s"build profile 含重复字段：${duplicateKeys.toSeq.sorted.mkString(", ")}")
    values
  }

  val (entry, construction) = CdeConfigResolver.resolve("", Set("spmv", "fpga"))
  implicit val parameters: Parameters = construction

  val values: Seq[(String, String)] = construction match {
    case value: CDEConfig with SpmvMadHiSpmvSimulationConstruction with MakeTerminal
        with RunnerPlanProvider =>
      val config = parameters(SpmvMadHiSpmvConfigKey).getOrElse(
        throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvMadHiSpmvConfigKey"))
      checked(
        SpmvMadHiSpmvSimulationProfile.values(entry, value, config) ++
          Seq("BUILD_RUNNER_ID" -> value.runnerPlan.runnerId) ++
          value.runnerPlan.profileValues)

    case value: CDEConfig with SpmvMadHiSpmvTapaFpgaRuntimeConstruction with MakeTerminal
        with FpgaToolchainConstruction with AcceleratorHostConstruction with FpgaAssetLayoutConfig
        with Construction =>
      val config = parameters(SpmvMadHiSpmvConfigKey).getOrElse(
        throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvMadHiSpmvConfigKey"))
      val extra = Seq("BUILD_RUNNER_ID" -> "fpga-build") ++
        value.fpgaToolchainConfig.profileValues ++
        fpgaPlatformValues
      checked(SpmvMadHiSpmvFpgaProfile.values(entry, value, config, extra, value.fpgaAssetLayout))

    case _ =>
      throw new IllegalArgumentException(
        s"${entry.className} 暂未提供 build profile；当前支持 MAD-HiSpMV 仿真和 U55C TAPA FPGA")
  }

  ConstructionProfile.write(Path.of(args(0)), values)
}
