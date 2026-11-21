<?php
// Push notifications with Firebase Cloud Messaging (FCM), sent by the SERVER (Assignment 3).
//
//  1. Each phone sends its FCM token after login (auth/fcm_token.php -> users.fcm_token).
//     Logging out clears it, so a logged-out phone gets nothing.
//  2. To notify someone we call the FCM HTTP v1 API:
//       POST https://fcm.googleapis.com/v1/projects/{project}/messages:send
//  3. That needs a Google access token, made from the service account key
//     backend/secrets/service-account.json (Firebase console -> Service accounts):
//       - a JWT (header.claims) signed with the key's RSA private key (openssl_sign, SHA256),
//       - exchanged at https://oauth2.googleapis.com/token for an access token (1 hour),
//       - kept in backend/secrets/fcm-token.json and reused until it expires.
//  4. Only "data" is sent; the app's KinnectMessagingService builds the notification.
//
// The key never leaves the server (backend/secrets is in .gitignore and denied to the web).
// A push that fails is only logged: sending the message itself still succeeds.
declare(strict_types=1);
require_once __DIR__ . '/api.php';

const PUSH_SECRETS = __DIR__ . '/../secrets';

/** A new chat message: "Aisha Khan" / "See you at 5". */
function push_new_message(int $from, int $to, array $message): void
{
    $sender = row('SELECT name FROM users WHERE id = ?', [$from]);
    push_to_user($to, [
        'type' => 'message',
        'title' => $sender['name'] ?? 'Kinnect',
        'body' => function_exists('message_preview') ? message_preview($message) : $message['text'],
        'chatId' => $message['chat_id'],
        'fromUid' => (string) $from,
    ]);
}

/** An incoming call (step 9): the phone rings even when the app is closed. */
function push_call(int $from, int $to, array $call): void
{
    $caller = row('SELECT name FROM users WHERE id = ?', [$from]);
    push_to_user($to, [
        'type' => 'call',
        'title' => $caller['name'] ?? 'Kinnect',
        'callId' => (string) $call['id'],
        'callType' => $call['type'],
        'fromUid' => (string) $from,
    ]);
}

/** A row on the Notifications screen (friend request, reaction, comment ...) as a push too. */
function push_alert(int $to, int $from, string $type, string $text): void
{
    $who = row('SELECT name FROM users WHERE id = ?', [$from])['name'] ?? 'Someone';
    $body = [
        'friend_request' => "$who sent you a friend request",
        'friend_accepted' => "$who accepted your friend request",
        'post_reaction' => "$who reacted to your post",
        'comment' => "$who commented on your post",
        'reply' => "$who replied to your comment",
        'comment_like' => "$who liked your comment",
        'story_reaction' => "$who reacted to your story",
    ][$type] ?? null;
    if ($body === null) return;
    push_to_user($to, ['type' => 'alert', 'title' => 'Kinnect', 'body' => $body, 'fromUid' => (string) $from]);
}

/** Sends [$data] to [$to]'s phone, if they have one logged in. Never throws. */
function push_to_user(int $to, array $data): void
{
    try {
        $user = row('SELECT fcm_token FROM users WHERE id = ?', [$to]);
        $token = $user['fcm_token'] ?? null;
        if (!$token) return;                         // no phone logged in for them
        $account = service_account();
        if ($account === null) return;               // key not installed: pushes are off
        $access = google_access_token($account);
        $data['toUid'] = (string) $to;               // the phone checks it's for the logged-in user
        $message = [
            'message' => [
                'token' => $token,
                'data' => array_map('strval', $data),
                'android' => ['priority' => 'HIGH'],
            ],
        ];
        $url = 'https://fcm.googleapis.com/v1/projects/' . $account['project_id'] . '/messages:send';
        [$status, $answer] = http_post($url, json_encode($message), 'application/json', $access);
        if ($status === 404 || $status === 400 && str_contains($answer, 'UNREGISTERED')) {
            exec_sql('UPDATE users SET fcm_token = NULL WHERE id = ?', [$to]);   // app removed: forget it
        } elseif ($status !== 200) {
            error_log("Kinnect push to $to failed: HTTP $status $answer");
        }
    } catch (Throwable $e) {
        error_log('Kinnect push failed: ' . $e->getMessage());
    }
}

/** backend/secrets/service-account.json, or null when it isn't there. */
function service_account(): ?array
{
    $file = PUSH_SECRETS . '/service-account.json';
    if (!is_file($file)) return null;
    return json_decode((string) file_get_contents($file), true) ?: null;
}

/** A Google access token for FCM, reused until a minute before it expires. */
function google_access_token(array $account): string
{
    $cache = PUSH_SECRETS . '/fcm-token.json';
    $saved = is_file($cache) ? json_decode((string) file_get_contents($cache), true) : null;
    if ($saved && $saved['expires'] > time() + 60) return $saved['token'];

    // 1. The JWT: base64url(header) . base64url(claims) . base64url(RS256 signature)
    $b64 = fn(string $s) => rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
    $now = time();
    $unsigned = $b64(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . $b64(json_encode([
        'iss' => $account['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => 'https://oauth2.googleapis.com/token',
        'iat' => $now,
        'exp' => $now + 3600,
    ]));
    if (!openssl_sign($unsigned, $signature, $account['private_key'], OPENSSL_ALGO_SHA256)) {
        throw new RuntimeException('Could not sign the JWT (is the private key correct?)');
    }
    $jwt = $unsigned . '.' . $b64($signature);

    // 2. Exchange it for an access token.
    $form = http_build_query(['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $jwt]);
    [$status, $answer] = http_post('https://oauth2.googleapis.com/token', $form, 'application/x-www-form-urlencoded');
    $json = json_decode($answer, true);
    if ($status !== 200 || empty($json['access_token'])) throw new RuntimeException("Token request failed: $answer");
    file_put_contents($cache, json_encode(['token' => $json['access_token'], 'expires' => $now + (int) $json['expires_in']]));
    return $json['access_token'];
}

/**
 * A small HTTPS POST with PHP's own streams (no extra library). Returns [status code, body].
 * XAMPP on Windows has no list of trusted certificates by default, so
 * backend/secrets/cacert.pem is used when it is there (see README).
 */
function http_post(string $url, string $body, string $type, ?string $bearer = null): array
{
    $headers = "Content-Type: $type\r\n";
    if ($bearer !== null) $headers .= "Authorization: Bearer $bearer\r\n";
    $ssl = ['verify_peer' => true, 'verify_peer_name' => true];
    if (is_file(PUSH_SECRETS . '/cacert.pem')) $ssl['cafile'] = PUSH_SECRETS . '/cacert.pem';
    $context = stream_context_create([
        'http' => ['method' => 'POST', 'header' => $headers, 'content' => $body, 'timeout' => 8, 'ignore_errors' => true],
        'ssl' => $ssl,
    ]);
    $answer = @file_get_contents($url, false, $context);
    if ($answer === false) throw new RuntimeException('Could not reach ' . parse_url($url, PHP_URL_HOST));
    $status = 0;
    foreach ($http_response_header ?? [] as $line) {
        if (preg_match('#^HTTP/\S+ (\d{3})#', $line, $m)) $status = (int) $m[1];
    }
    return [$status, $answer];
}
