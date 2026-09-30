package npc

import java.nio.file.Path
import org.chipsalliance.cde.config.{Config => CDEConfig, Parameters}

/** 将 Make Config 导出的 runner 计划写为公共 profile。 */
object DescribeRunnerPlan extends App {
  require(args.length == 1, "用法：npc.DescribeRunnerPlan <profile.env>")
  val requested = ConfigCatalog.selectedName("")
  val entry = ConfigCatalog.resolve(requested, Set("npc", "soc", "spmv", "fpga", "asic"))
  val construction = try {
    Class.forName(entry.className).getDeclaredConstructor().newInstance().asInstanceOf[CDEConfig]
  } catch {
    case error: ReflectiveOperationException =>
      throw new IllegalArgumentException(s"无法构造 Config ${entry.className}：${error.getMessage}", error)
  }
  implicit val parameters: Parameters = construction
  construction match {
    case value: CDEConfig with RunnerPlanProvider with MakeTerminal with Construction =>
      val plan = value.runnerPlan
      ConstructionProfile.write(
        Path.of(args(0)),
        Seq(
          "CONFIG_SHORT_NAME" -> entry.shortName,
          "CONFIG_FQCN" -> entry.className,
          "SCOPE" -> entry.scope,
          "CAPABILITY" -> value.capability,
          "TARGET" -> entry.target
        ) ++ plan.profileValues)
    case _ =>
      throw new IllegalArgumentException(
        s"${entry.className} 未提供 RunnerPlanProvider、MakeTerminal 和 Construction")
  }
}
