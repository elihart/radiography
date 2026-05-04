@file:OptIn(UiToolingDataApi::class)
package radiography.internal

import androidx.compose.ui.tooling.data.CallGroup
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Microbenchmark comparing the fast-path `if (data.isEmpty()) return emptySequence()` to the
 * pre-fast-path `data.asSequence().filter { ... }.mapNotNull { ... }` on a Group whose `data`
 * is always empty.
 *
 * Every recursive [Group.computeLayoutInfos] call fans out into [subComposedChildren], which
 * flat-maps [getCompositionContexts] over all children — even the Groups whose `data` carry
 * only Compose slot entries (the vast majority on Compose-only trees). Short-circuiting the
 * empty-data case skips three wrapper allocations (asSequence / filter / mapNotNull) per
 * empty Group.
 *
 * As with [AndroidViewChildrenFastPathBenchmark], the JIT can often escape-analyze the
 * wrapper allocations away in a microbenchmark, so we demand a conservative ≥ 2× speedup —
 * the real-world CPU win shows up as reduced allocation pressure across long-running test
 * workloads.
 */
internal class GetCompositionContextsFastPathBenchmark {

  @Test fun `fast-path is measurably faster than filter-pipeline on empty data`() {
    val iterations = 10_000
    val group = emptyDataGroup()

    val cold = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkCount = 0
      repeat(iterations) {
        // Pre-fast-path pipeline, inlined here so we don't have to modify production code.
        val seq = group.data.asSequence()
          .filter { false }
          .mapNotNull<Any?, Any> { null }
        sinkCount += seq.count()
      }
      sinkCount
    }

    val warm = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkCount = 0
      repeat(iterations) {
        sinkCount += group.getCompositionContexts().count()
      }
      sinkCount
    }

    println(
      "GetCompositionContextsFastPathBenchmark: cold=${cold}ns/batch warm=${warm}ns/batch " +
        "speedup=${cold.toDouble() / warm}x (batch = $iterations calls)"
    )
    assertThat(cold.toDouble() / warm).isAtLeast(2.0)
  }

  private fun emptyDataGroup(): Group = CallGroup(
    /* key = */ null,
    /* name = */ null,
    /* box = */ IntRect.Zero,
    /* location = */ null,
    /* identity = */ null,
    /* parameters = */ emptyList(),
    /* data = */ emptyList(),
    /* children = */ emptyList(),
    /* isInline = */ false,
  )

  private inline fun measureNanosAveraged(
    warmupIterations: Int,
    measuredIterations: Int,
    block: () -> Any,
  ): Long {
    repeat(warmupIterations) { block() }
    val start = System.nanoTime()
    var keepAlive = 0
    repeat(measuredIterations) {
      val result = block()
      if (result is Int) keepAlive += result
    }
    val elapsed = System.nanoTime() - start
    require(keepAlive >= Int.MIN_VALUE)
    return elapsed / measuredIterations
  }
}
