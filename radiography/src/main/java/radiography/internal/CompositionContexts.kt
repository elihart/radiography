package radiography.internal

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import kotlin.LazyThreadSafetyMode.PUBLICATION

private val REFLECTION_CONSTANTS by lazy(PUBLICATION) {
  try {
    object {
      val CompositionContextHolderClass =
        Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextHolder")
      val CompositionContextImplClass =
        Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextImpl")
      val ReusableRememberObserverHolderClass =
        Class.forName("androidx.compose.runtime.ReusableRememberObserverHolder")
      val CompositionContextHolderRefField =
        CompositionContextHolderClass.getDeclaredField("ref")
          .apply { isAccessible = true }
      val CompositionContextImplComposersField =
        CompositionContextImplClass.getDeclaredField("composers")
          .apply { isAccessible = true }
    }
  } catch (e: Throwable) {
    null
  }
}

@OptIn(UiToolingDataApi::class)
internal fun Group.getCompositionContexts(): Sequence<CompositionContext> {
  // Fast-path: most Groups have no `data` entries at all — skip the sequence + filter +
  // mapNotNull pipeline allocation for the common case. Compose-only trees almost never
  // carry a `CompositionContextHolder` directly on a Group's `data`, so this short-circuits
  // the vast majority of recursive calls cheaply.
  if (data.isEmpty()) return emptySequence()
  return REFLECTION_CONSTANTS?.run {
    data.asSequence()
      .filter { it != null && it::class.java == ReusableRememberObserverHolderClass }
      .mapNotNull { holder ->
        holder
          ?.let { holder::class.java.getMethod("getWrapped") }
          ?.invoke(holder)
          ?.tryGetCompositionContext()
      }
  } ?: emptySequence()
}

@Suppress("UNCHECKED_CAST")
internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
  return REFLECTION_CONSTANTS?.let {
    if (!it.CompositionContextImplClass.isInstance(this)) return emptyList()
    it.CompositionContextImplComposersField.get(this) as? Iterable<Composer>
  } ?: emptyList()
}

private fun Any?.tryGetCompositionContext() = REFLECTION_CONSTANTS?.let {
  it.CompositionContextHolderRefField.get(this) as? CompositionContext
}
