package io.github.neotheon3.orangebox.core.diagnostics

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Binds abstract fun bindDiagnosticsRepository(impl: ObdDiagnosticsRepository): DiagnosticsRepository

    /** Optional: the app binds it for the backend upload; other graphs (feature tests) leave it out. */
    @BindsOptionalOf abstract fun optionalDtcReportSink(): DtcReportSink
}
