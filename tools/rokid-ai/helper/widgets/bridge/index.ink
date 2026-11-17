<script def>
{ "widget": { "family": "1x1" } }
</script>
<script setup>
import { bridge, lifecycle } from '../../lib/relay.js';
export default {
  data: {},
  onCreate(options) {
    const create = String(options && options.probe) === 'true' ? bridge : lifecycle;
    this.connection = create(this, Object.assign({}, options, { client: 'widget' }));
    this.connection.start();
  },
  onAttach() {},
  onDetach() {},
  onDestroy() { if (this.connection) this.connection.stop(); },
};
</script>
<widget><view class="bridge-anchor" /></widget>
<style>
.bridge-anchor { width: 1px; height: 1px; }
</style>
