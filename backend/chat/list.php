<?php
// GET /chat/list.php  (logged in)  ->  {chats, online_friends, time}
// The Chats screen (20): one row per conversation, newest first:
//   {chat_id, other (user), last_message (preview text), last_sender_id, time, unread}
// online_friends: friends active in the last 2 minutes (the notes row).
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('GET');

$me = current_user_id();
$last = rows(
    'SELECT m.* FROM messages m
     JOIN (SELECT chat_id, MAX(id) AS last_id FROM messages WHERE sender_id = ? OR receiver_id = ? GROUP BY chat_id) t
       ON m.id = t.last_id
     ORDER BY m.id DESC LIMIT 100',
    [$me, $me]
);
$unread = [];
foreach (rows(
    'SELECT m.chat_id, COUNT(*) AS n FROM messages m
     LEFT JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
     WHERE m.receiver_id = ? AND m.deleted = 0 AND m.created_at > COALESCE(cm.read_at, 0)
     GROUP BY m.chat_id',
    [$me, $me]
) as $r) $unread[$r['chat_id']] = (int) $r['n'];

$otherIds = array_map(fn($m) => (int) $m['sender_id'] === $me ? (int) $m['receiver_id'] : (int) $m['sender_id'], $last);
$users = [];
foreach (users_by_ids(array_values(array_unique($otherIds)), $me) as $u) $users[$u['id']] = $u;

$chats = [];
foreach ($last as $i => $m) {
    $other = $users[$otherIds[$i]] ?? null;
    if ($other === null) continue;
    $chats[] = [
        'chat_id' => $m['chat_id'],
        'other' => $other,
        'last_message' => message_preview($m),
        'last_sender_id' => (int) $m['sender_id'],
        'time' => (int) $m['created_at'],
        'unread' => $unread[$m['chat_id']] ?? 0,
    ];
}
$online = array_values(array_filter(users_by_ids(friend_ids($me), $me), fn($u) => $u['online']));
respond(['chats' => $chats, 'online_friends' => $online, 'time' => now_ms()]);
