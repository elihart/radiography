package radiography.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests covering the caching invariants that back `SemanticsNodesCache`. The production
 * cache is a typealias for [IdKeyedCache]`<SemanticsNode>`, so validating [IdKeyedCache]
 * directly verifies the same invariants without needing a real Compose runtime to construct
 * real `SemanticsNode` instances.
 *
 * Verifies:
 *  - the underlying node fetcher is invoked at most once per cache instance
 *  - the cache faithfully filters by id (the single property [Group.computeLayoutInfos] looks
 *    at on each [SemanticsNode])
 *  - the returned `List<T>` preserves the order and identity of elements from the fetcher
 *    (i.e. no defensive copy that would diverge from the pre-cache filter-based behavior)
 */
internal class SemanticsNodesCacheTest {

  private data class IdNode(val id: Int)

  @Test
  fun `fetcher invoked once across many getById calls`() {
    val invocations = AtomicInteger(0)
    val allNodes = listOf(IdNode(1), IdNode(2), IdNode(3), IdNode(4))
    val cache = IdKeyedCache(
      fetchAll = {
        invocations.incrementAndGet()
        allNodes
      },
      idOf = IdNode::id,
    )

    repeat(100) { i ->
      cache.getById(i % 4 + 1)
    }

    assertThat(invocations.get()).isEqualTo(1)
  }

  @Test
  fun `getById returns items whose id matches and preserves identity`() {
    val n1a = IdNode(1)
    val n1b = IdNode(1)
    val n2 = IdNode(2)
    val cache = IdKeyedCache(
      fetchAll = { listOf(n1a, n1b, n2) },
      idOf = IdNode::id,
    )

    val byOne = cache.getById(1)
    val byTwo = cache.getById(2)
    val byThree = cache.getById(3)

    // Data-class equality here coincides with reference equality because each IdNode is unique.
    assertThat(byOne).containsExactly(n1a, n1b).inOrder()
    assertThat(byOne[0]).isSameInstanceAs(n1a)
    assertThat(byOne[1]).isSameInstanceAs(n1b)
    assertThat(byTwo).containsExactly(n2)
    assertThat(byTwo[0]).isSameInstanceAs(n2)
    assertThat(byThree).isEmpty()
  }

  @Test
  fun `getById with null id returns empty without invoking fetcher`() {
    var invoked = false
    val cache = IdKeyedCache<IdNode>(
      fetchAll = {
        invoked = true
        emptyList()
      },
      idOf = IdNode::id,
    )

    val result = cache.getById(null)

    assertThat(result).isEmpty()
    assertThat(invoked).isFalse()
  }

  @Test
  fun `null fetcher means every lookup returns empty without work`() {
    val cache = IdKeyedCache<IdNode>(fetchAll = null, idOf = IdNode::id)

    assertThat(cache.getById(42)).isEmpty()
    assertThat(cache.getById(null)).isEmpty()
  }

  @Test
  fun `null-id calls do not eagerly fetch and later id lookups succeed`() {
    val invocations = AtomicInteger(0)
    val nodes = listOf(IdNode(7))
    val cache = IdKeyedCache(
      fetchAll = {
        invocations.incrementAndGet()
        nodes
      },
      idOf = IdNode::id,
    )

    assertThat(cache.getById(null)).isEmpty()
    assertThat(invocations.get()).isEqualTo(0)

    assertThat(cache.getById(7)).containsExactly(nodes[0])
    assertThat(invocations.get()).isEqualTo(1)

    assertThat(cache.getById(7)).containsExactly(nodes[0])
    assertThat(invocations.get()).isEqualTo(1)
  }
}
