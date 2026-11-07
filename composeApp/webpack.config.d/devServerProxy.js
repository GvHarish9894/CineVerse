// Dev run (wasmJsBrowserDevelopmentRun) serves only the bundle; forward same-origin /api to the Ktor proxy.
// Start it with: PORT=8081 PUBLIC_ORIGIN=http://localhost:8080 COOKIE_SECURE=false ./gradlew :server:run
if (config.devServer) {
    // History routes (/movie/550, /search?q=…) must load index.html on refresh/deep link, like the server's STATIC_DIR fallback.
    config.devServer.historyApiFallback = true;
    config.devServer.proxy = [
        ...(config.devServer.proxy || []),
        { context: ["/api"], target: process.env.CINEVERSE_API || "http://localhost:8081" },
    ];
}
