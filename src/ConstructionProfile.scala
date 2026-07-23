package ysyx

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import _root_.scpu.{ConstructionProfile, FpgaConstruction, HostConstruction}
import _root_.scpu.fpga.{CdeConfigResolver, FpgaConfigParameters}

/** 为 L2 SoC 与 FPGA 终端 Config 生成规范化 profile。 */
object DescribeConfig extends App {
  require(args.length == 1, "用法：ysyx.DescribeConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("soc", "fpga"))
  val metadata: HostConstruction = construction
  implicit val parameters: Parameters = construction
  val npcConfig = FpgaConfigParameters.npcCoreConfig
  val extra = entry.board.toSeq.flatMap { _ =>
    val platform = FpgaConfigParameters.platform
    val fpga = construction match {
      case value: FpgaConstruction => value
      case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 FPGA 终端 trait")
    }
    val toolchain = fpga.fpgaToolchainConfig
    require(entry.board.contains(toolchain.device.board),
      s"Config catalog 板卡 ${entry.board.getOrElse("none")} 与工具链板卡 ${toolchain.device.board} 不一致")
    require(platform.board.name == toolchain.device.board,
      s"硬件 CDE 板卡 ${platform.board.name} 与工具链板卡 ${toolchain.device.board} 不一致")
    Seq(
      "FPGA_BOARD" -> platform.board.name,
      "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
      "FPGA_MEMORY_HOST_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.memoryHostBase, 16)}",
      "FPGA_CONTROL_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.controlBase, 16)}",
      "FPGA_MAILBOX_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.mailboxBase, 16)}",
      "FPGA_DIV_IP_CYCLES" -> platform.dividerIpCycles.toString,
      "FPGA_DIV_ADAPTER_CYCLES" -> platform.dividerAdapterCycles.toString
    ) ++ toolchain.profileValues
  }
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, metadata, npcConfig, extra)
  )
}
