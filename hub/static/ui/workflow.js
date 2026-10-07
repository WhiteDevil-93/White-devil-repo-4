/* Pure workflow rules shared by the UI and offline regression tests. */
(function (root) {
  'use strict';
  function eligibleCycleSource(job) {
    return job && /^[a-f0-9]{8,}$/i.test(job.id || '') && job.status === 'done' &&
      job.kind === 'chain' && !job.t2v && !job.from_job &&
      Array.isArray(job.lines) && job.lines.length > 0 &&
      Array.isArray(job.parts) && job.parts.length === job.lines.length;
  }
  function cyclePlan(jobs, id, rounds) {
    const job = jobs.find(j => j.id === id);
    if (!eligibleCycleSource(job)) throw new Error('Choose a completed image chain with per-part prompts.');
    const count = Number(rounds);
    if (!Number.isInteger(count) || count < 1 || count > 10) throw new Error('Choose 1–10 rounds.');
    return {body: {src: job.id, rounds: count}, name: job.name || job.id,
      // QA currently renders two candidate stacks per round; it may stop early.
      maxClips: count * 2 * job.parts.length};
  }
  function chatSaver(send, notify) {
    let queue = Promise.resolve(), version = 0;
    return payload => {
      const requested = ++version;
      notify('Saving to hub…');
      queue = queue.then(async () => {
        if (requested !== version) return;
        try {
          await send(payload);
          if (requested === version) notify('Saved to hub');
        } catch (_) {
          if (requested === version) notify('Hub sync failed — retry sync');
        }
      });
      return queue;
    };
  }
  const api = {eligibleCycleSource, cyclePlan, chatSaver};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.WdWorkflow = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
