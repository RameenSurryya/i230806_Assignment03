<?php
// POST /chat/typing.php  (logged in)  body {with, typing: true|false}
// "typing..." for the other person. It lasts 6 seconds unless renewed, so it disappears
// on its own if my app closes while I'm typing.
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('POST');

$me = current_user_id();
$typing = !in_array(param('typing', '1'), ['0', '', 'false'], true);
touch_member(chat_id($me, int_param('with')), $me, ['typing_until' => $typing ? now_ms() + 6000 : 0]);
respond();
