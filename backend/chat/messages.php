<?php
// GET /chat/messages.php?with=7&since=0  (logged in)
// The chat with user 7. since = 0: the newest 100 messages. since = the "time" of my last
// answer: only messages sent, edited, deleted or reacted to after that (cheap polling).
// Answer: {messages, other (user), friends, other_read_at, typing, time}
//   other_read_at  when they last opened the chat ("Seen" under my last message)
//   typing         true while they are typing
//   time           the server's clock: send it back as since, and use it for the 5-minute rule
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('GET');

$me = current_user_id();
$with = int_param('with');
$other = find_user($with);
if ($other === null) fail('User not found', 404);
$chatId = chat_id($me, $with);
$since = int_param('since');
$now = now_ms();

if ($since > 0) {
    // A few seconds of overlap, so nothing written during the last request is missed.
    $list = rows('SELECT * FROM messages WHERE chat_id = ? AND updated_at >= ? ORDER BY id', [$chatId, $since - 3000]);
} else {
    $list = array_reverse(rows('SELECT * FROM messages WHERE chat_id = ? ORDER BY id DESC LIMIT 100', [$chatId]));
}
$member = row('SELECT read_at, typing_until FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $with]);

respond([
    'messages' => messages_json($list),
    'other' => user_json($other, $me),
    'friends' => friend_status($me, $with) === 'friends',
    'other_read_at' => (int) ($member['read_at'] ?? 0),
    'typing' => (int) ($member['typing_until'] ?? 0) > $now,
    'time' => $now,
]);
