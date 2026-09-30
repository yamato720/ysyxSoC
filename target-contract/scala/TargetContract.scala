package target

/** RTL 输出交给 Verilator、FPGA 或 ASIC 后端时共享的最小交付合同。 */
sealed trait TargetKind {
  def name: String
}

object TargetKind {
  case object Verilator extends TargetKind {
    override val name = "verilator"
  }

  case object Fpga extends TargetKind {
    override val name = "fpga"
  }

  case object Asic extends TargetKind {
    override val name = "asic"
  }
}

/** RTL 构造完成后，后端只消费这些与工具无关的产物描述。 */
final case class RtlArtifact(
    topName: String,
    rtlDirectory: String,
    profilePath: String
)

/** 后端输入：RTL、构造 profile，以及目标类型。 */
final case class TargetInput(
    kind: TargetKind,
    artifact: RtlArtifact
)

/** 后端输出：可被 runner 继续消费的产物位置。 */
final case class TargetOutput(
    kind: TargetKind,
    artifactDirectory: String,
    manifestPath: String
)

trait TargetBackend {
  def kind: TargetKind
  def build(input: TargetInput): TargetOutput
}
