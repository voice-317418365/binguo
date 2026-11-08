// 账号接口验证脚本：cookie 从环境变量读，不落盘
// 用法：
//   Windows:  $env:HG_COOKIE="sessionid=xxx; sessionid_ss=xxx"; node tmp-verify.js
//   Linux:    HG_COOKIE="..." node tmp-verify.js
const hg = require('./hongguo-app.js');

const COOKIE = process.env.HG_COOKIE || '';
if (!COOKIE) {
  console.error('请设置环境变量 HG_COOKIE="sessionid=...; sessionid_ss=..."');
  process.exit(1);
}

(async () => {
  console.log('[1] 账号资料');
  try {
    const info = await hg.signedGet('/reading/user/info/v1/', {}, { Cookie: COOKIE });
    console.log('    code:', info.code, ' user:', info.data && info.data.user_name);
  } catch (e) {
    console.log('    失败:', e.message);
  }

  console.log('\n[2] 观看历史');
  try {
    const hist = await hg.signedGet(
      '/reading/bookapi/read_progress/list/v1/',
      { book_types: 2, limit: 50 },
      { Cookie: COOKIE }
    );
    const s = JSON.stringify(hist);
    console.log('    code:', hist.code, ' msg:', hist.message || '-', ' len:', s.length);
    console.log('    body:', s.slice(0, 1500));
  } catch (e) {
    console.log('    失败:', e.message);
  }

  console.log('\n[3] 收藏');
  try {
    const fav = await hg.signedGet('/reading/bookapi/bookshelf/video/info/v1/', {}, { Cookie: COOKIE });
    console.log('    code:', fav.code, ' msg:', fav.message || '-');
    console.log('    body:', JSON.stringify(fav).slice(0, 800));
  } catch (e) {
    console.log('    失败:', e.message);
  }
})();
