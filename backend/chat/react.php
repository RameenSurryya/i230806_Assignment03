<?php
// POST /chat/react.php  (logged in)  body {id, type}  type = love (double tap) ... or "" to remove.
// SETS my reaction, so a repeat from the offline queue is harmless.
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('POST');

$me = current_user_id();
$m = row('SELECT * FROM messages WHERE id = ?', [int_param('id')]);
if ($m === null || ((int) $m['sender_id'] !== $me && (int) $m['receiver_id'] !== $me)) fail('Message not found', 404);
$type = param('type', '') ?? '';
if ($type !== '' && !in_array($type, ['like', 'love', 'haha', 'wow', 'sad', 'angry'], true)) fail('Unknown reaction');

if ($type === '') exec_sql('DELETE FROM message_reactions WHERE message_id = ? AND user_id = ?', [(int) $m['id'], $me]);
else exec_sql(
    'INSERT INTO message_reactions (message_id, user_id, type) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE type = VALUES(type)',
    [(int) $m['id'], $me, $type]
);
exec_sql('UPDATE messages SET updated_at = ? WHERE id = ?', [now_ms(), (int) $m['id']]);
respond();
