<?php
// Calls (Assignment 3): ringing, answering and ending go through the server; the audio and
// video themselves go through Agora. Table calls = the ringing state AND the call log.
//
//   ringing  -> accepted (callee answered) -> ended (someone hung up)
//   ringing  -> declined (callee refused)
//   ringing  -> missed   (caller gave up / nobody answered in 45 s)
declare(strict_types=1);
require_once __DIR__ . '/friends.php';
require_once __DIR__ . '/agora.php';

const RING_TIMEOUT_MS = 45 * 1000;

/** A ringing call nobody answered in time becomes "missed" (checked whenever calls are read). */
function expire_unanswered_calls(): void
{
    $now = now_ms();
    exec_sql("UPDATE calls SET status = 'missed', ended_at = ? WHERE status = 'ringing' AND created_at < ?",
        [$now, $now - RING_TIMEOUT_MS]);
}

function call_json(array $c, int $me): array
{
    $outgoing = (int) $c['caller_id'] === $me;
    $answered = $c['answered_at'] === null ? 0 : (int) $c['answered_at'];
    $ended = $c['ended_at'] === null ? 0 : (int) $c['ended_at'];
    return [
        'id' => (int) $c['id'],
        'caller_id' => (int) $c['caller_id'],
        'callee_id' => (int) $c['callee_id'],
        'channel' => $c['channel'],
        'type' => $c['type'],
        'status' => $c['status'],
        'outgoing' => $outgoing,
        'created_at' => (int) $c['created_at'],
        'answered_at' => $answered,
        'ended_at' => $ended,
        'duration_sec' => $answered > 0 && $ended > $answered ? intdiv($ended - $answered, 1000) : 0,
    ];
}

/** A call I take part in, or a 404. */
function my_call(int $id, int $me): array
{
    $c = row('SELECT * FROM calls WHERE id = ? AND (caller_id = ? OR callee_id = ?)', [$id, $me, $me]);
    if ($c === null) fail('Call not found', 404);
    return $c;
}
