package npc

import java.nio.file.Path

/** 将 check terminal 声明的 Scala 或外部 runner 计划写给统一 Make runner。 */
object DescribeCheckPlan extends App {
  require(args.length == 1, "用法：npc.DescribeCheckPlan <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("npc", "soc", "spmv", "fpga", "asic"))
  require(construction.capability == ConstructionCapability.CheckOnly,
    s"${entry.className} 没有 check-only capability")
  val base = Seq(
    "CONFIG_SHORT_NAME" -> entry.shortName,
    "CONFIG_FQCN" -> entry.className,
    "SCOPE" -> entry.scope,
    "TARGET" -> entry.target,
    "CAPABILITY" -> construction.capability
  )
  val values = construction match {
    case value: CheckPlanProvider => base ++ value.checkPlan.profileValues
    case _ => throw new IllegalArgumentException(
      s"${entry.className} 未提供 CheckPlanProvider，check 必须由外部 runner 执行")
  }
  ConstructionProfile.write(
    Path.of(args(0)),
    values
  )
}
