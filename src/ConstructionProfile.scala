package ysyx

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import _root_.npc.{CdeConfigResolver, ConstructionProfile, HostConstruction, NpcCoreConfigKey}

/** 为 L2 SoC 终端 Config 生成规范化 profile。 */
object DescribeConfig extends App {
  require(args.length == 1, "用法：ysyx.DescribeConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("soc"))
  val metadata: HostConstruction = construction match {
    case host: HostConstruction => host
    case _ => throw new IllegalArgumentException(s"${entry.className} 不是可运行 SoC 终端")
  }
  implicit val parameters: Parameters = construction
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, metadata, parameters(NpcCoreConfigKey))
  )
}
