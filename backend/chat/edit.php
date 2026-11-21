<?php
// POST /chat/edit.php  (logged in)  body {id, text}
// Changes the text of MY message. Only within 5 minutes of sending: the SERVER checks the
// time (changeable_message), so a changed phone clock can't get around it. Answer: {message}.
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('POST');

$me = current_user_id();
$m = changeable_message(int_param('id'), $me);
$text = trim(required('text'));
if ($text === '') fail('Write a message');
if (!in_array($m['type'], ['text', 'image', 'video', 'post'], true)) fail('This message has no text to edit');

exec_sql('UPDATE messages SET text = ?, edited = 1, updated_at = ? WHERE id = ?', [$text, now_ms(), (int) $m['id']]);
respond(['message' => messages_json([row('SELECT * FROM messages WHERE id = ?', [(int) $m['id']])])[0]]);
