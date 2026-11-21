<?php
// POST /chat/send.php  (logged in, multipart or JSON)
// Fields: to (user id), type = text | image | video | file | post | system,
//         text, post_id (type post), reply_to_id (optional).
// File:   media (image, video or any allowed file) for types image / video / file.
// Sent with an X-Request-Id: a repeat from the offline queue returns the same message.
// Answer: {message}. The receiver gets a push (also for screenshot alerts).
declare(strict_types=1);
require_once __DIR__ . '/../lib/chat.php';
require_once __DIR__ . '/../lib/push.php';
method('POST');

$me = current_user_id();
$to = int_param('to');
$type = param('type', 'text') ?? 'text';
$text = param('text', '') ?? '';
$postId = int_param('post_id');
$replyTo = int_param('reply_to_id');
if (!in_array($type, ['text', 'image', 'video', 'file', 'post', 'system'], true)) fail('Unknown message type');
if ($to === $me || find_user($to) === null) fail('User not found', 404);

once($me, function () use ($me, $to, $type, $text, $postId, $replyTo) {
    $file = null;
    if (in_array($type, ['image', 'video', 'file'], true)) {
        $kinds = ['image' => ['image'], 'video' => ['video'], 'file' => ['image', 'video', 'file']][$type];
        $file = upload('media', 'chat', $kinds);
        if ($file === null) fail('Attach a file to send');
    } elseif ($type === 'post') {
        if (row('SELECT 1 FROM posts WHERE id = ?', [$postId]) === null) fail('Post not found', 404);
    } elseif (trim($text) === '') {
        fail('Write a message');
    }
    $chatId = chat_id($me, $to);
    if ($replyTo > 0 && row('SELECT 1 FROM messages WHERE id = ? AND chat_id = ?', [$replyTo, $chatId]) === null) {
        $replyTo = 0;   // replied-to message is gone: send it as a normal message
    }
    $now = now_ms();
    exec_sql(
        'INSERT INTO messages (chat_id, sender_id, receiver_id, type, text, media_path, file_name, file_size,
                               post_id, reply_to_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [$chatId, $me, $to, $type, $text, $file['path'] ?? null, $file['name'] ?? null, $file['size'] ?? null,
         $type === 'post' ? $postId : null, $replyTo > 0 ? $replyTo : null, $now, $now]
    );
    $message = row('SELECT * FROM messages WHERE id = ?', [(int) db()->lastInsertId()]);
    touch_member($chatId, $me, ['read_at' => $now, 'typing_until' => 0]);   // I've seen my own chat
    push_new_message($me, $to, $message);   // system lines too: "Aisha took a screenshot" is a required alert
    return ['message' => messages_json([$message])[0]];
});
