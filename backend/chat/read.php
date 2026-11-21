<?php
// POST /chat/read.php  (logged in)  body {with}: I opened the chat with this user.
// Clears my unread count for it and lets them see "Seen" under their last message.
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('POST');

$me = current_user_id();
touch_member(chat_id($me, int_param('with')), $me, ['read_at' => now_ms()]);
respond();
