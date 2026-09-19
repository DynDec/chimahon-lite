package mihon.buildlogic

import org.gradle.api.Project
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Git metadata is optional so source archives and exported worktrees can still build.
fun Project.getCommitCount(): String {
    return runCommand("git rev-list --count HEAD") ?: "0"
}

fun Project.getGitSha(): String {
    return runCommand("git rev-parse --short HEAD") ?: "unknown"
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLastCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLastCommitTime: Boolean): String {
    val epoch = if (useLastCommitTime) {
        runCommand("git log -1 --format=%ct")?.toLongOrNull()
    } else {
        null
    }

    return epoch?.let {
        Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    } ?: LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
}

private fun Project.runCommand(command: String): String? {
    return runCatching {
        providers.exec {
            commandLine = command.split(" ")
        }
            .standardOutput
            .asText
            .get()
            .trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
