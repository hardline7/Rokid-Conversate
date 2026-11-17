<script setup>
export default {
  onLoad() {
    console.log('ConversateAI cleanup-page-created');
    setTimeout(() => {
      console.log('ConversateAI cleanup-page-close-requested');
      window.close();
    }, 200);
  },
  onUnload() { console.log('ConversateAI cleanup-page-unloaded'); },
};
</script>
<page><view><text>회의 화면으로 돌아가는 중</text></view></page>
