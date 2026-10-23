package io.github.neotheon3.orangebox.core.common

import javax.inject.Qualifier

enum class OrangeBoxDispatcher { Default, IO }

/** Qualifier for injecting a [kotlinx.coroutines.CoroutineDispatcher]; replaced by test dispatchers in tests. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: OrangeBoxDispatcher)

/** Qualifier for the application-wide [kotlinx.coroutines.CoroutineScope]. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
