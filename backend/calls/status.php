<?php
// GET /calls/status.php?id=5  (logged in)  ->  {call}
// Both phones ask every 1.5 seconds while the call screen shows: ringing, accepted,
// declined, missed or ended.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('GET');

$me = current_user_id();
expire_unanswered_calls();
respond(['call' => call_json(my_call(int_param('id'), $me), $me)]);
