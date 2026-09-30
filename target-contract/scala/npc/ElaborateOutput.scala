package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** 各后端共用的 RTL 输出目录与黑盒资源清理工具。 */
private[npc] object ElaborateOutput {
  private val blackBoxFileListMarker = """// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"""

  def directory(default: String): String =
    sys.env.get("NPC_ELABORATE_OUTPUT_DIR").map(_.trim).filter(_.nonEmpty).getOrElse(default)

  def stripBlackBoxFileList(path: String): Unit = {
    val file = Path.of(path)
    if (Files.exists(file)) {
      val content = Files.readString(file, StandardCharsets.UTF_8)
      val markerIndex = content.indexOf(blackBoxFileListMarker)
      if (markerIndex >= 0) {
        Files.writeString(file, content.substring(0, markerIndex).stripTrailing + "\n", StandardCharsets.UTF_8)
      }
    }
  }
}
