<script setup>
import { bridge } from '../../lib/relay.js';
export default {
  data: { status: 'Connecting to Rokid AI', result: '' },
  onLoad(options) {
    console.log('ConversateAI page-created');
    this.relay = bridge(this, Object.assign({}, options, { client: 'page' }));
    this.relay.start();
  },
  onShow() { console.log('ConversateAI page-shown'); },
  onHide() { console.log('ConversateAI page-hidden'); },
  onUnload() { if (this.relay) this.relay.stop(); },
};
</script>
<page>
  <view class="panel">
    <text class="title">CONVERSATE AI</text>
    <text>{{status}}</text>
    <text class="result">{{result}}</text>
  </view>
</page>
<style>
.panel { display: flex; flex-direction: column; padding: 20px; color: #00ff00; background: #000000; }
.title { font-size: 24px; font-weight: bold; margin-bottom: 18px; }
.result { font-size: 20px; margin-top: 18px; }
</style>
