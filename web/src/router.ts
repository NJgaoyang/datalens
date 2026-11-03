import { createRouter, createWebHistory } from 'vue-router';
import FoundationView from './views/FoundationView.vue';
import PlannedModuleView from './views/PlannedModuleView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: FoundationView, meta: { title: '工程概览' } },
    { path: '/ask', component: PlannedModuleView, meta: { title: '智能问数', stage: 'M08', description: '先完成受控查询、业务语义和图表权限，再接入自然语言问数。' } },
    { path: '/charts/mine', component: PlannedModuleView, meta: { title: '我的图表', stage: 'M04', description: '确认保存后形成独立图表资产；再次打开时，重新查询 StarRocks。' } },
    { path: '/charts/shared', component: PlannedModuleView, meta: { title: '共享给我', stage: 'M05', description: '仅显示明确授权给当前用户的图表。链接和图表编号不构成访问权限。' } },
    { path: '/charts/public', component: PlannedModuleView, meta: { title: '全员共享', stage: 'M05', description: '全员指本系统的有效登录用户，不提供互联网匿名访问。' } },
    { path: '/settings', component: PlannedModuleView, meta: { title: '系统管理', stage: 'M01 / M02', description: '用户、只读数据源、模型和审计由服务端统一管理。M00 未创建任何默认账号。' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});
