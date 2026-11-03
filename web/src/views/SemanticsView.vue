<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { session } from '../api/auth';
import { listSubjects, metadata, fieldKey, type Subject, type Column } from '../api/datasources';
import { ApiError, errorMessage } from '../api/http';
import { emptySemanticDocument, parseSemanticDocument, readSemanticDraft, saveSemanticDraft, reviewSemanticDraft, publishSemantics, withdrawSemantics, readPublication, searchSemantics, listSemanticReleases, type SemanticDraft, type Publication, type SemanticSearch } from '../api/semantics';
const admin=computed(()=>session.user?.role==='SUPER_ADMIN');
const subjects=ref<Subject[]>([]), subject=ref(''), columns=ref<Column[]>([]);
const draft=ref<SemanticDraft>(), publication=ref<Publication>(), search=ref<SemanticSearch>(), releases=ref<Publication[]>([]);
const editor=ref(''), savedEditor=ref(''), query=ref(''), attested=ref(false), busy=ref(false), loading=ref(false), stale=ref(false), error=ref(''), notice=ref(''), publicError=ref('');
const dirty=computed(()=>editor.value!==savedEditor.value);
const canReview=computed(()=>!!draft.value?.document && !dirty.value && !stale.value && !draft.value.reviewed && attested.value);
const canPublish=computed(()=>!!draft.value?.reviewed && !dirty.value && !stale.value);
const kindLabels:Record<string,string>={FIELD:'字段',METRIC:'指标',DIMENSION:'维度',RELATION:'关联',EXAMPLE:'审核例题'};
const cardinalityLabels:Record<string,string>={ONE_TO_ONE:'一对一',MANY_TO_ONE:'多对一',ONE_TO_MANY:'一对多：可能重复累计',MANY_TO_MANY:'多对多：可能重复累计',UNKNOWN:'基数未知：禁止推断安全'};
let generation=0, controller:AbortController|undefined;
function begin(){generation++;controller?.abort();controller=new AbortController();return {epoch:generation,signal:controller.signal};}
function current(epoch:number){return epoch===generation;}
function clearContent(){draft.value=undefined;publication.value=undefined;search.value=undefined;releases.value=[];columns.value=[];editor.value='';savedEditor.value='';attested.value=false;stale.value=false;error.value='';publicError.value='';notice.value='';}
function adopt(value:SemanticDraft){draft.value=value;editor.value=JSON.stringify(value.document??emptySemanticDocument(),null,2);savedEditor.value=editor.value;attested.value=false;stale.value=false;}
function fail(cause:unknown){
  error.value=errorMessage(cause);notice.value='';
  if(cause instanceof ApiError && [401,403,404].includes(cause.status??0)){clearContent();error.value=errorMessage(cause);}
  else if(!(cause instanceof ApiError)||cause.status===409||['NETWORK_ERROR','INVALID_RESPONSE'].includes(cause.code)){stale.value=true;attested.value=false;publication.value=undefined;search.value=undefined;}
}
async function loadPublic(id:string,epoch:number,signal:AbortSignal){
  try{
    const value=await readPublication(id,signal);
    const matches=await searchSemantics(id,query.value.trim(),signal);
    if(!current(epoch))return;
    if(value.version!==matches.publicationVersion)throw new Error('发布版本在读取期间改变，请重新检索');
    publication.value=value;search.value=matches;publicError.value='';
  }catch(cause){
    if(!current(epoch)||signal.aborted)return;
    publication.value=undefined;search.value=undefined;
    if(cause instanceof ApiError && ['SEMANTIC_NOT_PUBLISHED','SEMANTIC_UNPUBLISHED'].includes(cause.code))publicError.value='此主题尚无有效发布版本。未发布草稿不会用于检索。';
    else if(cause instanceof ApiError && cause.status===401)fail(cause);
    else if(cause instanceof ApiError && cause.status===403 && !(admin.value&&draft.value?.document===null))fail(cause);
    else publicError.value=errorMessage(cause);
  }
}
async function selectSubject(){
  const {epoch,signal}=begin();clearContent();busy.value=false;loading.value=!!subject.value;
  if(!subject.value)return;
  const id=subject.value;
  try{
    if(admin.value){
      const value=await readSemanticDraft(id,signal);
      if(!current(epoch))return;adopt(value);
      try{const allowed=await metadata(id,false,signal);if(current(epoch))columns.value=allowed;}
      catch(cause){if(!current(epoch)||signal.aborted)return;if(cause instanceof ApiError&&cause.status===401)throw cause;columns.value=[];error.value=`当前授权字段不可读取：${errorMessage(cause)}。可按当前版本撤回发布。`;}
      if(!current(epoch))return;
    }
    await loadPublic(id,epoch,signal);
  }catch(cause){if(current(epoch)&&!signal.aborted)fail(cause);}
  finally{if(current(epoch))loading.value=false;}
}
async function initialize(){
  const {epoch,signal}=begin();loading.value=true;
  try{const values=await listSubjects(admin.value,signal);if(current(epoch))subjects.value=values;}
  catch(cause){if(current(epoch)&&!signal.aborted)fail(cause);}
  finally{if(current(epoch))loading.value=false;}
}
async function retrieve(){
  if(!subject.value||busy.value||loading.value)return;
  const {epoch,signal}=begin();loading.value=true;error.value='';publicError.value='';publication.value=undefined;search.value=undefined;
  try{await loadPublic(subject.value,epoch,signal);}finally{if(current(epoch))loading.value=false;}
}
async function mutate(action:'save'|'review'|'publish'|'withdraw'){
  if(busy.value||loading.value||!subject.value||!draft.value||stale.value)return;
  if(action==='review'&&!canReview.value||action==='publish'&&!canPublish.value)return;
  if(action==='withdraw'&&!window.confirm('撤回当前发布后，此主题的语义检索将立即停止。确认撤回？'))return;
  let savedDocument;
  if(action==='save'){try{savedDocument=parseSemanticDocument(editor.value);}catch(cause){error.value=errorMessage(cause);return;}}
  const {epoch,signal}=begin();busy.value=true;error.value='';notice.value='';
  const id=subject.value,version=draft.value.version;
  try{
    if(action==='save'){const value=await saveSemanticDraft(id,savedDocument!,version,signal);if(!current(epoch))return;adopt(value);notice.value='草稿已保存。任何修改都需要重新人工审核。';}
    else if(action==='review'){const value=await reviewSemanticDraft(id,version,signal);if(!current(epoch))return;adopt(value);notice.value='已记录人工审核确认；尚未发布。';}
    else if(action==='publish'){
      await publishSemantics(id,version,signal);if(!current(epoch))return;
      const value=await readSemanticDraft(id,signal);if(!current(epoch))return;adopt(value);notice.value='已发布不可变版本。审核记录不代表系统执行过例题。';
      await loadPublic(id,epoch,signal);
    }else{const value=await withdrawSemantics(id,version,signal);if(!current(epoch))return;adopt(value);publication.value=undefined;search.value=undefined;publicError.value='当前发布已撤回，语义检索已停止。';notice.value='已撤回当前发布；历史版本仅供管理员审计。';}
    releases.value=[];
  }catch(cause){if(current(epoch)&&!signal.aborted)fail(cause);}
  finally{if(current(epoch))busy.value=false;}
}
async function history(){
  if(!admin.value||busy.value||loading.value||!subject.value)return;
  const {epoch,signal}=begin();loading.value=true;error.value='';releases.value=[];
  try{const values=await listSemanticReleases(subject.value,signal);if(current(epoch))releases.value=values;}
  catch(cause){if(current(epoch)&&!signal.aborted)fail(cause);}
  finally{if(current(epoch))loading.value=false;}
}
watch(subject,selectSubject);
watch(editor,()=>{attested.value=false;});
watch(()=>session.user?.id,()=>{begin();clearContent();subjects.value=[];subject.value='';});
onMounted(initialize);onBeforeUnmount(()=>{generation++;controller?.abort();});
const schema=`{
  "fields": [{"id":"field_id", "name":"中文业务名称", "aliases":[], "physical":{"tableName":"授权表", "columnName":"授权字段"}, "type":"元数据类型", "unit":"单位或无", "sensitivity":"INTERNAL"}],
  "metrics": [{"id":"metric_id", "name":"指标中文名称", "aliases":[], "calculation":"金额/退款/去重口径", "filters":"有效状态与条件", "timeFieldId":"field_id", "owner":"业务负责人", "unit":"单位", "grain":"每行代表什么", "nullHandling":"空值处理", "timePolicy":"时区、起止边界", "fieldIds":["field_id"], "sql":"授权的单表只读 SQL", "parameters":[], "timeParameters":null}],
  "dimensions": [{"id":"dimension_id", "name":"维度名称", "aliases":[], "fieldId":"field_id"}],
  "relations": [{"id":"relation_id", "name":"关系名称", "leftFieldId":"field_id", "rightFieldId":"other_field_id", "cardinality":"ONE_TO_MANY", "leftGrain":"左表粒度", "rightGrain":"右表粒度"}],
  "examples": [{"id":"example_id", "question":"真实业务问题", "metricIds":["metric_id"], "sql":"已人工确认的单表只读 SQL", "parameters":[], "verification":"预期结果或明确的验证方法与人工核实记录"}]
}`;
</script>

<template><section class="page semantic-page">
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE / BUSINESS SEMANTICS</div><h1>业务语义与指标</h1><p class="subtitle">按当前主题权限检索已审核发布的业务口径，不使用未发布草稿推断答案。</p></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
  <el-alert v-if="notice" :title="notice" type="success" :closable="false" class="form-alert"/>
  <div class="panel semantic-controls chart-form">
    <label>业务主题<select v-model="subject" aria-label="语义业务主题" :disabled="busy"><option value="">选择获准访问的主题</option><option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
    <p v-if="!loading&&!subjects.length" class="scope-note">暂无可访问主题。请由管理员配置业务主题范围。</p>
    <form v-if="subject" class="semantic-search" @submit.prevent="retrieve"><label>名称、别名或业务问题<input v-model="query" aria-label="语义检索词" maxlength="200" :disabled="busy" placeholder="输入关键词；留空浏览当前发布资产"/></label><el-button native-type="submit" :loading="loading" :disabled="busy">检索已发布口径</el-button></form>
  </div>
  <div v-if="subject" class="panel semantic-publication">
    <div class="panel-heading"><h2>已发布业务知识</h2><span v-if="publication">发布版本 {{ publication.version }} · {{ publication.publishedAt }}</span></div>
    <p v-if="publicError" role="status" class="semantic-warning">{{ publicError }}</p>
    <p v-else-if="loading">正在重新验证权限与发布版本…</p>
    <template v-if="publication&&search">
      <p class="scope-note">人工审核人 {{ publication.reviewerId }}；发布人 {{ publication.publisherId }}。当前版本内容不可改写，所有检索均重新检查权限。遇到同名候选请比较口径，不自动选定。</p>
      <p v-if="!search.matches.length" role="status">没有匹配的已发布资产，请更换关键词。不会回退到草稿或猜测口径。</p>
      <ul class="semantic-matches"><li v-for="match in search.matches" :key="`${match.kind}:${match.id}`"><strong>{{ kindLabels[match.kind]??match.kind }} · {{ match.name }}</strong><span>{{ match.reasons.join('；') }}</span><code>{{ match.id }}</code></li></ul>
      <details class="semantic-details"><summary>查看当前发布版本的完整定义</summary>
        <h3>字段</h3><ul><li v-for="field in publication.document.fields" :key="field.id"><strong>{{ field.name }}</strong> · {{ field.aliases.join(' / ') }} · {{ fieldKey(field.physical) }} · {{ field.type }} · {{ field.unit }} · {{ field.sensitivity }}</li></ul>
        <h3>指标口径</h3><article v-for="metric in publication.document.metrics" :key="metric.id" class="semantic-card"><h4>{{ metric.name }} <small>{{ metric.id }}</small></h4><dl><dt>计算口径</dt><dd>{{ metric.calculation }}</dd><dt>过滤条件</dt><dd>{{ metric.filters }}</dd><dt>粒度与单位</dt><dd>{{ metric.grain }} · {{ metric.unit }}</dd><dt>时间字段 / 规则</dt><dd>{{ metric.timeFieldId }} / {{ metric.timePolicy }}</dd><dt>可替换时间参数</dt><dd v-if="metric.timeParameters">{{ metric.timeParameters.parameterType }} · {{ metric.timeParameters.timeZone }} · 起始索引 {{ metric.timeParameters.startIndex }} / 结束索引 {{ metric.timeParameters.endIndex }}（结束不含）</dd><dd v-else>未审核时间参数，不支持“上个月”替换</dd><dt>空值处理</dt><dd>{{ metric.nullHandling }}</dd><dt>负责人</dt><dd>{{ metric.owner }}</dd></dl><pre>{{ metric.sql }}</pre></article>
        <h3>维度</h3><ul><li v-for="dimension in publication.document.dimensions" :key="dimension.id">{{ dimension.name }} → {{ dimension.fieldId }}</li></ul>
        <h3>表关系与粒度风险</h3><ul><li v-for="relation in publication.document.relations" :key="relation.id">{{ relation.name }}：{{ relation.leftFieldId }} = {{ relation.rightFieldId }} · {{ cardinalityLabels[relation.cardinality] }} · {{ relation.leftGrain }} → {{ relation.rightGrain }}</li></ul><p class="semantic-warning">关系基数是人工声明，不证明唯一性。当前不允许发布跨表或 JOIN 的指标及例题 SQL，防止重复累计。</p>
        <h3>审核例题</h3><article v-for="example in publication.document.examples" :key="example.id" class="semantic-card"><h4>{{ example.question }}</h4><p>指标：{{ example.metricIds.join('、') }}</p><pre>{{ example.sql }}</pre><p>人工验证依据：{{ example.verification }}</p></article>
      </details>
    </template>
  </div>
  <div v-if="admin&&subject&&draft" class="panel semantic-editor">
    <div class="panel-heading"><h2>管理员草稿与发布</h2><span>草稿版本 {{ draft.version }} · {{ draft.reviewed&&!dirty ? '已人工审核' : '未审核' }}</span></div>
    <p class="semantic-warning">先选择一个真实主题，并准备约 20–30 道人工确认问题。系统只校验安全范围与结构，不执行例题，也不替代业务负责人确认口径。</p>
    <p v-if="!draft.document&&draft.version>0" class="scope-note">当前响应未包含可编辑内容。请重新载入草稿；如授权字段发生变化，需按新范围重新填写并审核。</p>
    <p v-if="draft.publishedVersion">当前发布版本：{{ draft.publishedVersion }}。保存草稿不会自动替换已发布内容。</p>
    <ul v-if="draft.warnings.length" class="semantic-warning"><li v-for="warning in draft.warnings" :key="warning">{{ warning }}</li></ul>
    <details class="semantic-details"><summary>编辑格式与当前授权字段</summary><p>fields、metrics、examples 必须至少一项；dimensions、relations 可为空。ID 在文档内唯一；物理字段必须来自下列授权元数据。字段最多 200 项，其余类别各 100 项。示意内容不是业务事实，必须替换后再保存。</p><pre>{{ schema }}</pre><p>可选 timeParameters 示例：{"startIndex":0,"endIndex":1,"timeZone":"Asia/Shanghai","parameterType":"DATE"}。索引从 0 开始，必须对应 timeFieldId 的 WHERE 时间字段 &gt;= ? 与时间字段 &lt; ?，并经人工审核。TIMESTAMP 使用业务月边界转换后的 UTC Instant；未配置则不允许相对月份替换。SQL 和其他参数保持不变。</p><p>敏感级别：PUBLIC / INTERNAL / SENSITIVE。关系类型：ONE_TO_ONE / MANY_TO_ONE / ONE_TO_MANY / MANY_TO_MANY / UNKNOWN。参数使用 type 与字符串 value，NULL 值为 null。</p><ul><li v-for="column in columns" :key="fieldKey(column)">{{ fieldKey(column) }} · {{ column.typeName }}</li></ul></details>
    <div class="chart-form"><label>语义草稿 JSON<textarea v-model="editor" aria-label="语义草稿 JSON" rows="19" maxlength="300000" spellcheck="false" :disabled="busy||loading||stale"/></label></div>
    <p v-if="dirty" class="scope-note">有未保存修改。请先保存；原审核结论不能用于新内容。</p>
    <p v-if="stale" role="alert" class="semantic-warning">操作结果未确认或版本已变化。请重新载入服务端状态后再操作，不会自动重试发布。</p>
    <div class="semantic-actions"><el-button :disabled="busy||loading||stale" @click="mutate('save')">保存草稿</el-button><el-button :disabled="busy||loading" @click="selectSubject">重新载入（放弃本地修改）</el-button><el-button :disabled="busy||loading" @click="history">查看发布历史</el-button></div>
    <div class="semantic-review"><label><input v-model="attested" type="checkbox" aria-label="人工审核确认" :disabled="busy||loading||dirty||stale||!draft.document||draft.reviewed"/> 我已人工核实此保存版本的业务口径、粒度风险及例题验证依据。</label><div class="semantic-actions"><el-button :disabled="busy||loading||!canReview" @click="mutate('review')">记录人工审核</el-button><el-button type="primary" :disabled="busy||loading||!canPublish" @click="mutate('publish')">发布已审核版本</el-button><el-button type="danger" plain :disabled="busy||loading||stale||!draft.publishedVersion" @click="mutate('withdraw')">撤回当前发布</el-button></div></div>
    <p v-if="draft.reviewedAt" class="scope-note">审核人 {{ draft.reviewerId }} · {{ draft.reviewedAt }}</p>
    <ul v-if="releases.length" class="semantic-history"><li v-for="release in releases" :key="release.version">版本 {{ release.version }} · {{ release.publishedAt }} · 审核人 {{ release.reviewerId }} · 发布人 {{ release.publisherId }}<small>摘要：{{ release.digest }}</small></li></ul>
  </div>
</section></template>

<style scoped>
.semantic-controls,.semantic-publication,.semantic-editor{margin-bottom:24px}.semantic-controls{max-width:none}.semantic-search{display:flex;align-items:end;gap:16px}.semantic-search label{flex:1}.semantic-warning{color:#865019;background:#fff8e8;padding:14px;border-radius:6px;font-size:13px;line-height:1.8}.semantic-matches{padding:0;list-style:none}.semantic-matches li{display:grid;gap:7px;border-bottom:1px solid #e2e8f0;padding:18px 0}.semantic-matches span,.semantic-matches code{font-size:12px;color:#64748b}.semantic-details{margin:20px 0;font-size:13px;line-height:1.9}.semantic-details summary{cursor:pointer;color:#2865ad;font-weight:600}.semantic-details pre{background:#f3f6fa;white-space:pre-wrap;overflow-wrap:anywhere;padding:14px;font-size:12px}.semantic-details h3{margin-top:20px}.semantic-details ul{padding-left:20px}.semantic-card{border:1px solid #e2e8f0;border-radius:7px;padding:18px;margin:12px 0}.semantic-card dl{display:grid;grid-template-columns:140px 1fr;gap:7px}.semantic-card dt{color:#64748b}.semantic-card dd{margin:0}.semantic-card small{color:#64748b;font-weight:400}.semantic-editor textarea{font-family:ui-monospace,monospace;font-size:12px;line-height:1.6}.semantic-actions{display:flex;flex-wrap:wrap;gap:10px;margin:18px 0}.semantic-actions .el-button{margin-left:0}.semantic-review{margin-top:24px;border-top:1px solid #e2e8f0;padding-top:20px;font-size:13px;line-height:1.8}.semantic-review label{display:flex;align-items:start;gap:8px}.semantic-review input{margin-top:5px}.semantic-history{padding-left:20px;font-size:13px;line-height:1.9}.semantic-history small{display:block;overflow-wrap:anywhere;color:#64748b}.semantic-page p,.semantic-page li,.semantic-page dd{overflow-wrap:anywhere}@media(max-width:760px){.semantic-search{align-items:stretch;flex-direction:column}.semantic-card dl{grid-template-columns:1fr}.semantic-publication .panel-heading,.semantic-editor .panel-heading{align-items:start;flex-direction:column;gap:12px}}
</style>
