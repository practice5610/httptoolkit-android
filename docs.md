# Ludo Interceptor – How It Works (Simple Overview)

This document explains the flow between the VPN app and the server in plain terms for non-technical readers.

---

## What the user sees

1. **First time (or after “Change API key”)**  
   The app shows a screen where the user must enter an **API key** (and optionally the server URL).  
   They get this key from you (or your admin).  
   They tap **Continue**. The app checks the key with the server.  
   - If the key is valid → the app saves it and shows the main screen (Connect / Disconnect).  
   - If not → the app shows an error and they stay on the same screen.

2. **Main screen**  
   The user sees **Connect**, **Reconnect**, and **Disconnect**, plus a **Change API key** option.  
   They tap **Connect** when they want to start intercepting Ludo King traffic.

3. **After connecting**  
   The app sets up the VPN and may prompt to install a certificate.  
   Once connected, the user can open the Ludo King game (manually or, if implemented, the app can try to open it).  
   While they use Ludo King, the app captures the game’s auth token in the background and sends it to your server together with their API key.

---

## Flow in three steps

### Step 1 – You create keys (your side only)

- You (or an admin) call the server’s **Create API** to generate a new API key.  
- The server creates a unique key and stores it in the database.  
- You give that key to the user (e.g. by email, dashboard, or support).

**User does not see this step.** They only receive the key from you.

---

### Step 2 – User enters the key in the app (verify)

- User opens the VPN app.  
- If no API key is saved, the app shows the **API key screen**.  
- User pastes the key you gave them (and server URL if needed) and taps **Continue**.  
- The app sends that key to the server’s **Verify API**.  
- If the server says the key is valid → the app saves the key and shows the main screen (Connect / Disconnect).  
- If not → the app shows “Invalid API key” and they stay on the API key screen.

So: **Verify** = “Is this key allowed to use the app?” Only valid keys get past this screen.

---

### Step 3 – User connects VPN and uses Ludo King (token is sent to server)

- On the main screen, the user taps **Connect**.  
- The app turns on the VPN and (if you configured it) may try to open Ludo King.  
- When the user uses Ludo King, the game talks to its servers. The VPN app sits in the middle and can see that traffic.  
- When the app sees the **auth token** that Ludo King uses to identify the player, it:  
  - Sends that token to your server together with the user’s **API key**.  
- Your server’s **Save-token API** receives: API key + auth token.  
- The server finds the user by API key and **saves the auth token** in the database (e.g. for that user/device).  
- Later you can use that stored token (e.g. in your backend or tools) to act on behalf of that Ludo King account, as needed.

So: **Create** = you create keys; **Verify** = app checks the key when the user enters it; **Save token** = app sends the captured Ludo auth token to the server, linked to that key.

---

## Summary table

| Step | Who does it | What happens |
|------|-------------|--------------|
| Create key | You (admin) | Server generates a key and stores it. You give the key to the user. |
| Enter key & Continue | User | App sends the key to the server. If valid, app saves it and shows Connect/Disconnect. |
| Connect VPN & use Ludo King | User | App captures Ludo’s auth token and sends it to the server with the user’s API key. |
| Save token | Server | Server stores the auth token for that API key (that user/device). |

---

## In one sentence

**You create and hand out API keys; the app checks the key when the user enters it, then when they connect and use Ludo King the app sends the game’s auth token to your server, which stores it under that key.**
