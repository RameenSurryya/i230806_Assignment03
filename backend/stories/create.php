<?php
// POST /stories/create.php  (logged in, multipart)
// File: media (a photo or a video). Fields: label (text sticker), audience (public | close).
// The story expires 24 hours after the server receives it. Sent with an X-Request-Id, so a
// repeat from the offline queue returns the same story instead of making a second one.
declare(strict_types=1);
require_once __DIR__ . '/../lib/stories.php';
method('POST');

$me = current_user_id();
$label = mb_substr(trim(param('label', '') ?? ''), 0, 100);
$audience = param('audience', 'public') === 'close' ? 'close' : 'public';

once($me, function () use ($me, $label, $audience) {
    $file = upload('media', 'stories', ['image', 'video']);
    if ($file === null) fail('Add a photo or a video');

    $now = now_ms();
    exec_sql(
        'INSERT INTO stories (user_id, media_path, media_type, label, audience, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)',
        [$me, $file['path'], $file['kind'], $label, $audience, $now, $now + STORY_LIFETIME_MS]
    );
    $id = (int) db()->lastInsertId();
    return ['story' => stories_json([row('SELECT * FROM stories WHERE id = ?', [$id])], $me)[0]];
});
