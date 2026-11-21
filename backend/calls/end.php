<?php
// POST /calls/end.php  (logged in)  body {id}
// Either side hangs up. A call that was never answered becomes "missed", an answered one
// "ended" (its length = ended_at - answered_at). Ending twice changes nothing.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('POST');

$me = current_user_id();
$c = my_call(int_param('id'), $me);
$now = now_ms();
if ($c['status'] === 'ringing') {
    exec_sql("UPDATE calls SET status = 'missed', ended_at = ? WHERE id = ?", [$now, (int) $c['id']]);
} elseif ($c['status'] === 'accepted') {
    exec_sql("UPDATE calls SET status = 'ended', ended_at = ? WHERE id = ?", [$now, (int) $c['id']]);
}
respond(['call' => call_json(my_call((int) $c['id'], $me), $me)]);
