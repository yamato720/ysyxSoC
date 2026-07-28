package ysyx

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import _root_.npc.{CdeConfigResolver, ConstructionProfile, HostConstruction, NpcCoreConfigKey}

/** 为 L2 SoC 终端 Config 生成规范化 profile。 */
object DescribeConfig extends App {
  require(args.length == 1, "用法：ysyx.DescribeConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("soc"))
  val metadata: HostConstruction = construction
  implicit val parameters: Parameters = construction
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, metadata, parameters(NpcCoreConfigKey))
  )
}
