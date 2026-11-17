<script def>
{ "widget": { "family": "1x1" } }
</script>
<script setup>
import { bridge } from '../../lib/relay.js';
export default {
  data: {},
  onCreate(options) {
    console.log('ConversateAI widget-created');
    this.relay = bridge(this, Object.assign({}, options, { client: 'widget' }));
    this.relay.start();
  },
  onAttach() { if (this.relay) this.relay.wake(); },
  // Detach also occurs when the meeting app opens; keep its bridge available.
  onDetach() {},
  onDestroy() {
    console.log('ConversateAI widget-destroyed');
    if (this.relay) this.relay.stop();
  },
};
</script>
<widget><view class="bridge-anchor" /></widget>
<style>
.bridge-anchor { width: 1px; height: 1px; }
</style>
