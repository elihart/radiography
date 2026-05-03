@file:OptIn(InternalComposeUiApi::class, UiToolingDataApi::class)
package radiography.internal

import android.widget.TextView
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.InteroperableComposeUiNode
import androidx.compose.ui.tooling.data.CallGroup
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for the [Group.androidViewChildren] fast-path. Verifies that:
 *  - Groups with empty `data` skip the `mapNotNull` allocation entirely (behavioral equivalence)
 *  - Groups whose `data` contains an [InteroperableComposeUiNode] still find it through the
 *    slow path (the fast-path does not silently drop interop views)
 *  - Groups whose `data` contains only non-interop objects return an empty list (behavioral
 *    equivalence with the pre-fast-path version)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
internal class AndroidViewChildrenFastPathTest {

  @Test fun `empty data returns emptyList via fast-path`() {
    val group = groupWithData(data = emptyList())

    val result = group.androidViewChildren()

    assertThat(result).isEmpty()
  }

  @Test fun `data with single interop node is returned`() {
    val view = TextView(RuntimeEnvironment.getApplication())
    val interopNode = mock(InteroperableComposeUiNode::class.java)
    `when`(interopNode.getInteropView()).thenReturn(view)
    val group = groupWithData(data = listOf(interopNode))

    val result = group.androidViewChildren()

    assertThat(result).hasSize(1)
    assertThat(result[0].view).isSameInstanceAs(view)
  }

  @Test fun `data with only non-interop objects returns empty`() {
    val group = groupWithData(data = listOf("foo", 42, Any()))

    val result = group.androidViewChildren()

    assertThat(result).isEmpty()
  }

  @Test fun `data with mixed interop and non-interop returns only the interop ones`() {
    val view1 = TextView(RuntimeEnvironment.getApplication())
    val view2 = TextView(RuntimeEnvironment.getApplication())
    val interop1 = mock(InteroperableComposeUiNode::class.java).also {
      `when`(it.getInteropView()).thenReturn(view1)
    }
    val interop2 = mock(InteroperableComposeUiNode::class.java).also {
      `when`(it.getInteropView()).thenReturn(view2)
    }
    val interopNoView = mock(InteroperableComposeUiNode::class.java).also {
      `when`(it.getInteropView()).thenReturn(null)
    }
    val group = groupWithData(data = listOf(interop1, "junk", interop2, interopNoView, 42))

    val result = group.androidViewChildren()

    assertThat(result.map { it.view }).containsExactly(view1, view2).inOrder()
  }

  private fun groupWithData(data: List<Any?>): Group = CallGroup(
    /* key = */ null,
    /* name = */ null,
    /* box = */ IntRect.Zero,
    /* location = */ null,
    /* identity = */ null,
    /* parameters = */ emptyList(),
    /* data = */ data,
    /* children = */ emptyList(),
    /* isInline = */ false,
  )
}
