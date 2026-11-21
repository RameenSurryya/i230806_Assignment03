<?php
// POST /chat/delete.php  (logged in)  body {id}
// Deletes MY message for everyone (it then shows "Message deleted"), only within 5 minutes:
// checked by the server. The photo / video / file is removed from the disk too.
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
method('POST');

$me = current_user_id();
$m = changeable_message(int_param('id'), $me);
delete_file($m['media_path']);
exec_sql(
    "UPDATE messages SET deleted = 1, text = '', media_path = NULL, file_name = NULL, file_size = NULL,
            updated_at = ? WHERE id = ?",
    [now_ms(), (int) $m['id']]
);
exec_sql('DELETE FROM message_reactions WHERE message_id = ?', [(int) $m['id']]);
respond(['message' => messages_json([row('SELECT * FROM messages WHERE id = ?', [(int) $m['id']])])[0]]);
