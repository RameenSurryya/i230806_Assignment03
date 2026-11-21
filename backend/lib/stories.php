<?php
// Stories (Assignment 3): shared by the stories/ endpoints.
//
//   stories (id, user_id, media_path, media_type, label, audience, created_at, expires_at)
//   story_views (story_id, viewer_id, viewed_at, reaction)   "seen by" + reactions
//
// 24-hour expiry is enforced HERE: every query only returns stories with expires_at in the
// future, and purge_expired_stories() deletes old rows and their files from the disk.
declare(strict_types=1);
require_once __DIR__ . '/friends.php';
require_once __DIR__ . '/upload.php';

const STORY_LIFETIME_MS = 24 * 60 * 60 * 1000;

/** Deletes every expired story (rows, views, and the photo / video file). */
function purge_expired_stories(): void
{
    $now = now_ms();
    foreach (rows('SELECT media_path FROM stories WHERE expires_at <= ?', [$now]) as $r) delete_file($r['media_path']);
    exec_sql('DELETE FROM stories WHERE expires_at <= ?', [$now]);   // story_views: ON DELETE CASCADE
}

/**
 * SQL condition: stories [$me] may see right now. Mine, and my friends' that haven't expired;
 * a close-friends story only if its author put me in their close friends.
 * Arguments to bind: [now, me, me, me].
 */
function visible_stories_sql(): string
{
    return "s.expires_at > ? AND (
        s.user_id = ?
        OR (s.user_id IN (SELECT friend_id FROM friends WHERE user_id = ?)
            AND (s.audience = 'public'
                 OR EXISTS (SELECT 1 FROM close_friends c WHERE c.user_id = s.user_id AND c.friend_id = ?))))";
}

/**
 * Story rows as API JSON. viewers = {"<user id>": viewed_at}, reactions = {"<user id>": type}.
 * For MY stories they list everyone (Seen by); for other people's only my own entry, so
 * nobody sees who else watched someone's story.
 */
function stories_json(array $stories, int $me): array
{
    if (!$stories) return [];
    $ids = array_map(fn($s) => (int) $s['id'], $stories);
    $marks = implode(',', array_fill(0, count($ids), '?'));
    $views = [];
    foreach (rows("SELECT * FROM story_views WHERE story_id IN ($marks)", $ids) as $v) $views[(int) $v['story_id']][] = $v;

    $authorIds = array_values(array_unique(array_map(fn($s) => (int) $s['user_id'], $stories)));
    $authors = [];
    foreach (users_by_ids($authorIds, $me) as $u) $authors[$u['id']] = $u;

    $result = [];
    foreach ($stories as $s) {
        $id = (int) $s['id'];
        $author = (int) $s['user_id'];
        $viewers = [];
        $reactions = [];
        foreach ($views[$id] ?? [] as $v) {
            $viewer = (int) $v['viewer_id'];
            if ($author !== $me && $viewer !== $me) continue;
            $viewers[(string) $viewer] = (int) $v['viewed_at'];
            if ($v['reaction'] !== null) $reactions[(string) $viewer] = $v['reaction'];
        }
        $result[] = [
            'id' => $id,
            'author_id' => $author,
            'author' => $authors[$author] ?? null,
            'media_url' => media_url($s['media_path']),
            'media_type' => $s['media_type'],
            'label' => $s['label'],
            'audience' => $s['audience'],
            'created_at' => (int) $s['created_at'],
            'expires_at' => (int) $s['expires_at'],
            'viewers' => (object) $viewers,
            'reactions' => (object) $reactions,
        ];
    }
    return $result;
}

/** One story I may see, or a 404. */
function visible_story(int $id, int $me): array
{
    $story = row('SELECT s.* FROM stories s WHERE s.id = ? AND ' . visible_stories_sql(), [$id, now_ms(), $me, $me, $me]);
    if ($story === null) fail('This story has expired or is not available', 404);
    return $story;
}
