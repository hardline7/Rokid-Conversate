<script def>
{ "widget": { "family": "1x1" } }
</script>
<script setup>
import { bridge } from '../../lib/relay.js';
export default {
  data: { status: 'Connecting to Rokid AI', result: '' },
  onCreate(options) {
    console.log('ConversateAI widget-created');
    this.relay = bridge(this, Object.assign({}, options, { client: 'widget' }));
    this.relay.start();
  },
  onAttach() { console.log('ConversateAI widget-attached'); },
  onDetach() { console.log('ConversateAI widget-detached'); },
  onDestroy() {
    console.log('ConversateAI widget-destroyed');
    if (this.relay) this.relay.stop();
  },
};
</script>
<widget>
  <view class="panel">
    <text class="title">CONVERSATE AI</text>
    <text>{{status}}</text>
    <text class="result">{{result}}</text>
  </view>
</widget>
<style>
.panel { display: flex; flex-direction: column; padding: 10px; color: #00ff00; background: #000000; }
.title { font-size: 16px; font-weight: bold; margin-bottom: 8px; }
.result { font-size: 14px; margin-top: 8px; }
</style>
