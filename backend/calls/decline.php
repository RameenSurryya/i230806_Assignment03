<?php
// POST /calls/decline.php  (logged in)  body {id}: the callee refuses a ringing call.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('POST');

$me = current_user_id();
$c = my_call(int_param('id'), $me);
if ($c['status'] === 'ringing') {
    exec_sql("UPDATE calls SET status = 'declined', ended_at = ? WHERE id = ?", [now_ms(), (int) $c['id']]);
}
respond(['call' => call_json(my_call((int) $c['id'], $me), $me)]);
