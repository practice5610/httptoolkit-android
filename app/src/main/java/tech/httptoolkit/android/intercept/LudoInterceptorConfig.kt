package tech.httptoolkit.android.intercept

object LudoInterceptorConfig {
    const val TARGET_PACKAGE = "com.ludo.king"
    const val TARGET_HOST = "misc-services.ludokingapi.com"
    const val TARGET_PATH = "/api/v3/player/profile"
    const val SOCKET_HOST = "services.ludokingapi.com"
    const val SOCKET_PATH_PREFIX = "/v7/socket.io/"
    const val LOCAL_PROXY_PORT = 8118
    /** Default server base URL for API key verification and token upload (e.g. emulator: http://10.0.2.2:3000). No trailing slash. */
    const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:3000"
}
