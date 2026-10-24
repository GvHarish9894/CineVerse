package com.techgv.cineverse.di

import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/** The browser host owns one isolated graph and explicitly starts/stops its asynchronous runtime. */
class BrowserApplication {
    val graph: KoinApplication = koinApplication { modules(sharedModules(WebConfig("/api"))) }
    val runtime: WebRuntime = graph.koin.get()

    init { runtime.start() }

    fun stop() { runtime.stop(); graph.close() }
}
