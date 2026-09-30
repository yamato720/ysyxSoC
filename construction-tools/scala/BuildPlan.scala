package ysyx

import java.nio.file.Path

import org.chipsalliance.cde.config.Parameters
import _root_.npc.{BuildProfileProvider, CdeConfigResolver, ConstructionProfile}

/** 调用终端自己的投影，公共入口只验证字段合同。 */
object DescribeBuildPlan extends App {
  require(args.length == 1, "用法：ysyx.DescribeBuildPlan <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("spmv", "fpga", "asic"))
  val provider = construction match {
    case value: BuildProfileProvider => value
    case _ => throw new IllegalArgumentException(s"${entry.className} 未提供 build profile")
  }
  val values = provider.buildProfile(entry, construction: Parameters)
  val duplicateKeys = values.groupBy(_._1).collect { case (key, entries) if entries.size > 1 => key }
  require(duplicateKeys.isEmpty,
    s"${entry.className} 的 build profile 含重复字段：${duplicateKeys.toSeq.sorted.mkString(", ")}")
  val fields = values.toMap
  require(fields.get("CONFIG_FQCN").contains(entry.className) &&
    fields.get("SCOPE").contains(entry.scope) &&
    fields.get("TARGET").contains(entry.target) &&
    fields.get("CAPABILITY").contains(construction.capability),
    s"${entry.className} 的 build profile 身份字段不一致")
  require(fields.get("BUILD_RUNNER_BUNDLE_ID").exists(_.nonEmpty) &&
    fields.get("BUILD_RUNNER_BUNDLE_SOURCE").exists(_.nonEmpty),
    s"${entry.className} 缺少 build runner")
  if (construction.capability == "run") {
    require(fields.get("RUNNER_ID").exists(_.nonEmpty) &&
      fields.get("RUNNER_BUNDLE_ID") == fields.get("RUNNER_ID") &&
      fields.get("RUNNER_BUNDLE_SOURCE") == fields.get("BUILD_RUNNER_BUNDLE_SOURCE"),
      s"${entry.className} 缺少 run runner")
  }
  ConstructionProfile.write(Path.of(args(0)), values)
}
