<?php
// POST /calls/answer.php  (logged in)  body {id}
// The callee answers a ringing call: accepted. Answer {call, token} to join the channel.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('POST');

$me = current_user_id();
expire_unanswered_calls();
$c = my_call(int_param('id'), $me);
if ((int) $c['callee_id'] !== $me) fail('Only the person being called can answer', 403);
if ($c['status'] !== 'ringing') fail('This call has ended', 409);
exec_sql("UPDATE calls SET status = 'accepted', answered_at = ? WHERE id = ?", [now_ms(), (int) $c['id']]);
$c = my_call((int) $c['id'], $me);
respond(['call' => call_json($c, $me), 'token' => agora_token($c['channel'])]);
