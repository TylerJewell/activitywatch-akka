// The server's own feed of what changed, subscribed to rather than asked for.
//
// The server this interface talks to publishes every change to every bucket on
// `/api/0/stream` as server-sent events. Each message is a bucket's *current* row —
// its metadata, how many events it holds, when it was last updated, and the change
// that produced the row — so applying one is idempotent and a reconnection that
// re-delivers a row costs nothing.
//
// `changes` counts every change the bucket has had. A subscriber that sees it jump
// by more than one knows a burst was coalesced into a single row and re-reads the
// span it is showing; that is what stops a busy watcher from making a view drift.
//
// There is a second stream, per bucket, at `/api/0/buckets/<id>/stream`. The
// whole-server one is a projection: complete, resumable, and about a second behind,
// because each poll of it deliberately looks back far enough to tolerate two writers'
// clocks disagreeing. The per-bucket one is pushed from the bucket as it is written and
// arrives in tens of milliseconds. A view showing one bucket live calls `follow` and
// gets the second; everything else follows the first.

import { defineStore } from 'pinia';
import { IBucket } from '~/util/interfaces';
import { useBucketsStore } from '~/stores/buckets';

interface BucketRow {
  bucket: string;
  name?: string;
  type: string;
  client: string;
  hostname: string;
  created: string;
  createdMillis: number;
  dataJson: string;
  events: number;
  lastUpdatedMillis?: number;
  changes: number;
  lastChangeKind?: string;
  lastChangeJson?: string;
}

// One change to one bucket, as the per-bucket stream sends it.
interface BucketChange {
  bucket: string;
  kind: string;
  id: number | null;
  event: { id: number | null; timestamp: string; duration: number; data: any } | null;
  events: number;
}

interface State {
  connected: boolean;
  // Buckets whose contents moved since a view last read them, by bucket id.
  dirty: Record<string, number>;
  // The change count last seen per bucket, so a coalesced burst is detectable.
  seen: Record<string, number>;
  lastMessageAt: number | null;
  // The most recent change to each followed bucket, for a view drawing it live.
  latest: Record<string, BucketChange>;
}

let source: EventSource | null = null;
const followed: Record<string, EventSource> = {};

export const useStreamStore = defineStore('stream', {
  state: (): State => ({
    connected: false,
    dirty: {},
    seen: {},
    lastMessageAt: null,
    latest: {},
  }),

  actions: {
    // Opens the stream, once per page. The browser reconnects on its own and sends
    // back the last event id it saw, which the server uses to resume.
    subscribe(): void {
      if (source !== null || typeof EventSource === 'undefined') {
        return;
      }
      source = new EventSource('/api/0/stream');
      source.onopen = () => {
        this.connected = true;
      };
      source.onerror = () => {
        this.connected = false;
      };
      source.onmessage = (message: MessageEvent) => {
        this.connected = true;
        this.lastMessageAt = Date.now();
        try {
          this.apply(JSON.parse(message.data) as BucketRow);
        } catch (e) {
          console.error('unreadable message on the change stream', e);
        }
      };
    },

    // Applies one row to the buckets store, without asking the server anything.
    apply(row: BucketRow): void {
      const buckets = useBucketsStore();
      const previous = this.seen[row.bucket];
      this.seen = { ...this.seen, [row.bucket]: row.changes };
      if (previous === undefined || row.changes > previous) {
        this.dirty = { ...this.dirty, [row.bucket]: row.changes };
      }

      const known = buckets.buckets.slice();
      const index = known.findIndex(b => b.id === row.bucket);
      const data = row.dataJson ? JSON.parse(row.dataJson) : {};
      const bucket: IBucket = {
        id: row.bucket,
        type: row.type,
        hostname: row.hostname,
        device_id: data.device_id,
        data,
        created: new Date(row.created),
        first_seen: new Date(row.created),
        last_updated: row.lastUpdatedMillis ? new Date(row.lastUpdatedMillis) : undefined,
      };
      if (index >= 0) {
        known[index] = { ...known[index], ...bucket };
      } else {
        known.push(bucket);
      }
      buckets.update_buckets(known);
    },

    // Follows one bucket closely. Opening the same one twice is a no-op, so a view may
    // call this on every render without keeping track of whether it already has.
    follow(bucketId: string): void {
      if (followed[bucketId] !== undefined || typeof EventSource === 'undefined') {
        return;
      }
      const source = new EventSource('/api/0/buckets/' + bucketId + '/stream');
      source.onmessage = (message: MessageEvent) => {
        try {
          const change = JSON.parse(message.data) as BucketChange;
          this.latest = { ...this.latest, [change.bucket]: change };
          this.dirty = { ...this.dirty, [change.bucket]: change.events };
        } catch (e) {
          console.error('unreadable message on a bucket stream', e);
        }
      };
      followed[bucketId] = source;
    },

    unfollow(bucketId: string): void {
      const source = followed[bucketId];
      if (source !== undefined) {
        source.close();
        delete followed[bucketId];
      }
    },

    // Marks a bucket as read up to the change count a view has just fetched.
    settle(bucketId: string): void {
      const { [bucketId]: _dropped, ...rest } = this.dirty;
      this.dirty = rest;
    },

    // A bucket a view is showing has moved since it last read it.
    changed(bucketId: string): boolean {
      return this.dirty[bucketId] !== undefined;
    },

    close(): void {
      if (source !== null) {
        source.close();
        source = null;
      }
      Object.keys(followed).forEach(bucketId => this.unfollow(bucketId));
      this.connected = false;
    },
  },
});
