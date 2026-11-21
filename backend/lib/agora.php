<?php
// Agora call tokens made on the SERVER (Assignment 3), so the App Certificate never ships
// inside the app. Same format as Agora's official RtcTokenBuilder2 (token version "007"):
//
//   content   = appId, issue time, expiry, salt, and the RTC service with its privileges
//               (join the channel, publish audio / video / data) for one channel name
//   signature = HMAC-SHA256 of the content, keyed with the certificate mixed with time + salt
//   token     = "007" + base64( zlib( signature + content ) )
//
// The App ID is in config.php ('agora_app_id'); the certificate in
// backend/secrets/agora-certificate.txt (both kept out of Git).
declare(strict_types=1);
require_once __DIR__ . '/api.php';

const AGORA_VALID_SECONDS = 3600;

/** Little-endian packing, like Agora's: uint16 = 'v', uint32 = 'V', text = length + bytes. */
function agora_short(int $v): string { return pack('v', $v); }
function agora_int(int $v): string { return pack('V', $v); }
function agora_string(string $s): string { return pack('v', strlen($s)) . $s; }

/** A token for [$channel] that lets any uid join and send audio / video, or null if not set up. */
function agora_token(string $channel): ?string
{
    $appId = (string) (config()['agora_app_id'] ?? '');
    $file = __DIR__ . '/../secrets/agora-certificate.txt';
    $certificate = is_file($file) ? trim((string) file_get_contents($file)) : '';
    $hex32 = fn(string $s) => (bool) preg_match('/^[0-9a-fA-F]{32}$/', $s);
    if (!$hex32($appId) || !$hex32($certificate)) return null;

    $issuedAt = time();
    $salt = random_int(1, 99999999);
    $content = agora_string($appId)
        . agora_int($issuedAt) . agora_int(AGORA_VALID_SECONDS) . agora_int($salt)
        . agora_short(1)                         // one service: RTC
        . agora_short(1)                         // RTC = 1
        . agora_short(4)                         // four privileges, each valid for an hour
        . agora_short(1) . agora_int(AGORA_VALID_SECONDS)   // join channel
        . agora_short(2) . agora_int(AGORA_VALID_SECONDS)   // publish audio
        . agora_short(3) . agora_int(AGORA_VALID_SECONDS)   // publish video
        . agora_short(4) . agora_int(AGORA_VALID_SECONDS)   // publish data
        . agora_string($channel)
        . agora_string('');                      // uid "" = any uid may use it

    // Signing key: HMAC(key = issue time, data = certificate), then HMAC(key = salt, data = that).
    $step1 = hash_hmac('sha256', $certificate, agora_int($issuedAt), true);
    $signingKey = hash_hmac('sha256', $step1, agora_int($salt), true);
    $signature = hash_hmac('sha256', $content, $signingKey, true);

    return '007' . base64_encode(gzcompress(agora_string($signature) . $content));
}
