# How to Filter Logs in Logcat

## Quick Filter for Ludoking VPN Logs

In Android Studio Logcat, use this filter:

**Tag:** `LudokingVPN`

This will show only logs from:
- LocalProxyServer
- TokenInterceptor  
- TokenService

## Filter Options

### Option 1: Tag Filter
1. Open Logcat in Android Studio
2. In the filter box at the top, type: `tag:LudokingVPN`
3. Press Enter

### Option 2: Regex Filter
Use this regex pattern:
```
tag:LudokingVPN
```

### Option 3: Package Filter
Filter by package name:
```
package:tech.httptoolkit.android
```

## What to Look For

### When VPN Starts:
- `[PROXY] *** Local proxy server started on port 8000 ***`

### When Requests Come In:
- `[PROXY] Received CONNECT request: ...`
- `[PROXY] Connecting to: misc-services.ludokingapi.com:443`

### When Ludoking Request Detected:
- `[PROXY] *** LUDOKING REQUEST DETECTED ***`
- `[PROXY] *** PROFILE API REQUEST FOUND ***`

### When Token is Extracted:
- `[TOKEN] *** TOKEN EXTRACTED: ... ***`
- `[INTERCEPTOR] Token intercepted from: ...`
- `[SERVICE] Saving token to backend: ...`
- `[SERVICE] Token save response: 200` or `201`

## Command Line (adb logcat)

If using adb from command line:

```bash
adb logcat -s LudokingVPN
```

Or with more detail:
```bash
adb logcat | grep -i "LudokingVPN\|PROXY\|TOKEN\|INTERCEPTOR\|SERVICE"
```

## Common Issues to Check

1. **No proxy logs**: VPN might not be routing to proxy
2. **No CONNECT requests**: Traffic might not be reaching proxy
3. **SSL handshake errors**: Certificate generation issue
4. **No token extraction**: Request format might be different
5. **Token save fails**: Backend connection issue
