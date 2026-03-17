package tech.httptoolkit.android

object IntentExtras {
    const val SCANNED_URL_EXTRA = "tech.httptoolkit.android.SCANNED_URL"
    const val SELECTED_PORTS_EXTRA = "tech.httptoolkit.android.SELECTED_PORTS_EXTRA"
    const val UNSELECTED_APPS_EXTRA = "tech.httptoolkit.android.UNSELECTED_APPS_EXTRA"
    const val PROXY_CONFIG_EXTRA = "tech.httptoolkit.android.PROXY_CONFIG"
    const val UNINTERCEPTED_APPS_EXTRA = "tech.httptoolkit.android.UNINTERCEPTED_APPS"
    const val INTERCEPTED_PORTS_EXTRA = "tech.httptoolkit.android.INTERCEPTED_PORTS"
}

object Constants {
    const val QR_CODE_URL_PREFIX = "https://android.httptoolkit.tech/connect/"
    
    // Backend API configuration
    const val BACKEND_BASE_URL = "http://192.168.10.2:5000" // Default, can be overridden via SharedPreferences
    const val BACKEND_API_V1 = "$BACKEND_BASE_URL/api/v1"
    
    // Ludoking game configuration
    const val LUDOKING_PACKAGE_NAME = "com.ludo.king"
    const val LUDOKING_PROFILE_API_URL = "https://misc-services.ludokingapi.com/api/v3/player/profile"
    
    // PWA configuration
    const val PWA_URL = "https://justtapit.co/"
    
    // Local proxy server configuration
    const val LOCAL_PROXY_PORT = 8000
    const val LOCAL_PROXY_HOST = "127.0.0.1"
}
