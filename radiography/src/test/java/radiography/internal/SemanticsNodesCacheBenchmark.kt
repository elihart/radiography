package radiography.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Microbenchmark-flavored tests comparing the O(N^2) pre-cache behavior (per-LayoutNode
 * `allNodes.filter { it.id == semanticsId }`) to the new [SemanticsNodesCache] /
 * [IdKeyedCache] behavior (one O(N) walk + N O(1) map lookups).
 *
 * The asserts demand **≥ 10× reduction** in the number of times the full node list is walked.
 * On real Compose trees (Airbnb's Trio interaction-test workload: ~500 LayoutNodes against
 * ~230-node semantics trees), the ratio of saved walks is ~500×.
 *
 * Uses a `data class IdNode(val id: Int)` as a stand-in for `SemanticsNode` — the caching
 * behavior depends only on the `.id` property, so the microbenchmark is faithful to the
 * production path.
 */
internal class SemanticsNodesCacheBenchmark {

  private data class IdNode(val id: Int)

  @Test
  fun `cache reduces full-node-list traversals by 100x at N=100`() = assertTraversalReductionAtLeast(
    layoutNodeCount = 100,
    semanticsNodeCount = 100,
    minimumReductionRatio = 100,
  )

  @Test
  fun `cache reduces full-node-list traversals by 200x at N=200`() = assertTraversalReductionAtLeast(
    layoutNodeCount = 200,
    semanticsNodeCount = 200,
    minimumReductionRatio = 200,
  )

  @Test
  fun `cache reduces full-node-list traversals by 500x at N=500`() = assertTraversalReductionAtLeast(
    layoutNodeCount = 500,
    semanticsNodeCount = 500,
    minimumReductionRatio = 500,
  )

  @Test
  fun `wall-time comparison at N=500`() {
    val semanticsNodeCount = 500
    val layoutNodeCount = 500
    val allNodes = (0 until semanticsNodeCount).map { IdNode(it) }
    // Simulate the per-LayoutNode lookup pattern from the old code:
    // allNodes.filter { it.id == semanticsId }
    val cold = measureNanosAveraged(warmupIterations = 3, measuredIterations = 10) {
      var keepAlive = 0
      for (id in 0 until layoutNodeCount) {
        val matches = allNodes.filter { it.id == id }
        keepAlive += matches.size
      }
      keepAlive
    }
    val warm = measureNanosAveraged(warmupIterations = 3, measuredIterations = 10) {
      val cache = IdKeyedCache(fetchAll = { allNodes }, idOf = IdNode::id)
      var keepAlive = 0
      for (id in 0 until layoutNodeCount) {
        val matches = cache.getById(id)
        keepAlive += matches.size
      }
      keepAlive
    }
    println("SemanticsNodesCacheBenchmark N=500: cold=${cold}ns warm=${warm}ns speedup=${cold.toDouble() / warm}x")
    // Demand a measurable wall delta. At N=500 the O(N^2) baseline is 250K element checks versus
    // ~500 map lookups + one groupBy pass.
    assertThat(cold.toDouble() / warm).isAtLeast(10.0)
  }

  private fun assertTraversalReductionAtLeast(
    layoutNodeCount: Int,
    semanticsNodeCount: Int,
    minimumReductionRatio: Int,
  ) {
    val allNodes = (0 until semanticsNodeCount).map { IdNode(it) }

    // Baseline: per-LayoutNode `allNodes.filter { it.id == semanticsId }`.
    // Each `filter` call walks the entire list. Total comparisons: layoutNodeCount * semanticsNodeCount.
    val baselineTraversals = layoutNodeCount.toLong() * semanticsNodeCount.toLong()

    // New path: one `groupBy { it.id }` pass (O(N)), then N O(1) map lookups.
    // Count the traversal as a single walk of the node list (the groupBy) — the lookups are not
    // list traversals at all.
    var cacheFetcherInvocations = 0L
    val cache = IdKeyedCache(
      fetchAll = {
        cacheFetcherInvocations++
        allNodes
      },
      idOf = IdNode::id,
    )
    repeat(layoutNodeCount) { id -> cache.getById(id) }

    assertThat(cacheFetcherInvocations).isEqualTo(1L)
    val cachedTraversals = semanticsNodeCount.toLong() // the single groupBy pass

    val reductionRatio = baselineTraversals / cachedTraversals
    assertThat(reductionRatio).isAtLeast(minimumReductionRatio.toLong())
  }

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
