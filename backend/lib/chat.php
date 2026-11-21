<?php
// Chat (Assignment 3): shared by the chat/ endpoints.
//
//   messages (chat_id, sender_id, receiver_id, type, text, media_path, file_name, file_size,
//             post_id, reply_to_id, edited, deleted, created_at, updated_at)
//   message_reactions (message_id, user_id, type)
//   chat_members (chat_id, user_id, read_at, typing_until)      "Seen" and "typing..."
//
// chat_id = smaller user id + "_" + bigger user id, so both people use the same one.
// updated_at changes on every edit, delete and reaction, so the app can ask only for what
// changed since its last check (?since=...).
declare(strict_types=1);
require_once __DIR__ . '/friends.php';

/** Your own messages can be edited or deleted for 5 minutes. Checked HERE, on the server. */
const EDIT_WINDOW_MS = 5 * 60 * 1000;

/** Message rows as API JSON, with reactions and the replied-to message's text. */
function messages_json(array $list): array
{
    if (!$list) return [];
    $ids = array_map(fn($m) => (int) $m['id'], $list);
    $marks = implode(',', array_fill(0, count($ids), '?'));
    $reactions = [];
    foreach (rows("SELECT * FROM message_reactions WHERE message_id IN ($marks)", $ids) as $r) {
        $reactions[(int) $r['message_id']][(string) $r['user_id']] = $r['type'];
    }
    $replyIds = array_values(array_filter(array_map(fn($m) => (int) $m['reply_to_id'], $list)));
    $replies = [];
    if ($replyIds) {
        $marks = implode(',', array_fill(0, count($replyIds), '?'));
        foreach (rows("SELECT id, sender_id, type, text, deleted FROM messages WHERE id IN ($marks)", $replyIds) as $r) {
            $replies[(int) $r['id']] = $r;
        }
    }
    $result = [];
    foreach ($list as $m) {
        $id = (int) $m['id'];
        $reply = $replies[(int) $m['reply_to_id']] ?? null;
        $result[] = [
            'id' => $id,
            'chat_id' => $m['chat_id'],
            'sender_id' => (int) $m['sender_id'],
            'receiver_id' => (int) $m['receiver_id'],
            'type' => $m['type'],
            'text' => $m['text'],
            'media_url' => media_url($m['media_path']),
            'file_name' => $m['file_name'],
            'file_size' => $m['file_size'] === null ? 0 : (int) $m['file_size'],
            'post_id' => $m['post_id'] === null ? null : (int) $m['post_id'],
            'reply_to_id' => $reply === null ? null : (int) $reply['id'],
            'reply_to_text' => $reply === null ? '' : ((int) $reply['deleted'] === 1 ? '' : $reply['text']),
            'reply_to_type' => $reply['type'] ?? '',
            'reply_to_sender_id' => $reply === null ? null : (int) $reply['sender_id'],
            'edited' => (int) $m['edited'] === 1,
            'deleted' => (int) $m['deleted'] === 1,
            'created_at' => (int) $m['created_at'],
            'updated_at' => (int) $m['updated_at'],
            'reactions' => (object) ($reactions[$id] ?? []),
        ];
    }
    return $result;
}

/** One line for the Chats list: the text, or "Photo", "Video", "File: x.pdf" ... */
function message_preview(array $m): string
{
    if ((int) $m['deleted'] === 1) return 'Message deleted';
    switch ($m['type']) {
        case 'image': return $m['text'] !== '' ? $m['text'] : 'Photo';
        case 'video': return $m['text'] !== '' ? $m['text'] : 'Video';
        case 'file':  return 'File: ' . ($m['file_name'] ?? '');
        case 'post':  return $m['text'] !== '' ? $m['text'] : 'Shared a post';
        default:      return $m['text'];
    }
}

/** A message of mine that I may still change, or a clear error (checked on the server). */
function changeable_message(int $id, int $me): array
{
    $m = row('SELECT * FROM messages WHERE id = ?', [$id]);
    if ($m === null) fail('Message not found', 404);
    if ((int) $m['sender_id'] !== $me) fail('You can only change your own messages', 403);
    if ((int) $m['deleted'] === 1) fail('This message was deleted', 409);
    if ($m['type'] === 'system') fail('This message cannot be changed', 403);
    if (now_ms() - (int) $m['created_at'] > EDIT_WINDOW_MS) {
        fail('Messages can only be edited or deleted within 5 minutes', 403);
    }
    return $m;
}

/** chat_members row for [$uid] in [$chatId], created on first use. */
function touch_member(string $chatId, int $uid, array $set): void
{
    $read = $set['read_at'] ?? 0;
    $typing = $set['typing_until'] ?? 0;
    $sql = 'INSERT INTO chat_members (chat_id, user_id, read_at, typing_until) VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE ';
    $parts = [];
    if (array_key_exists('read_at', $set)) $parts[] = 'read_at = VALUES(read_at)';
    if (array_key_exists('typing_until', $set)) $parts[] = 'typing_until = VALUES(typing_until)';
    exec_sql($sql . implode(', ', $parts), [$chatId, $uid, $read, $typing]);
}
