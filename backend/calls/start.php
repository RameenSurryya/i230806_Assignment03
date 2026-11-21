<?php
// POST /calls/start.php  (logged in)  body {to, type: voice | video}
// Starts ringing [to]: saves the call, sends them a call push (their phone rings even when
// the app is closed) and answers {call, token, app_id} so I can join the Agora channel.
// 409 "busy" when they are already in a call.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('POST');

$me = current_user_id();
$to = int_param('to');
$type = param('type', 'voice') === 'video' ? 'video' : 'voice';
if ($to === $me || find_user($to) === null) fail('User not found', 404);

expire_unanswered_calls();
$busy = row(
    "SELECT 1 FROM calls WHERE status IN ('ringing', 'accepted') AND created_at > ?
     AND (caller_id IN (?, ?) OR callee_id IN (?, ?))",
    [now_ms() - 2 * 60 * 60 * 1000, $to, $me, $to, $me]
);
if ($busy !== null) fail('busy', 409);

$channel = 'call_' . bin2hex(random_bytes(8));   // the Agora channel both phones join
exec_sql(
    "INSERT INTO calls (caller_id, callee_id, channel, type, status, created_at) VALUES (?, ?, ?, ?, 'ringing', ?)",
    [$me, $to, $channel, $type, now_ms()]
);
$call = row('SELECT * FROM calls WHERE id = ?', [(int) db()->lastInsertId()]);
push_call($me, $to, $call);
respond(['call' => call_json($call, $me), 'token' => agora_token($channel), 'app_id' => config()['agora_app_id'] ?? '']);
