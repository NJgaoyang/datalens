import { createRouter, createWebHistory } from 'vue-router';
import { session, loadSession, clearSession } from './api/auth';
import { ApiError, errorMessage, setUnauthorizedHandler } from './api/http';
import LoginView from './views/LoginView.vue';
import PasswordView from './views/PasswordView.vue';
import UsersView from './views/UsersView.vue';
import FoundationView from './views/FoundationView.vue';
import SourcesView from './views/SourcesView.vue';
import SubjectsView from './views/SubjectsView.vue';
import QueryView from './views/QueryView.vue';
const ChartsView = () => import('./views/ChartsView.vue');
const ModelView = () => import('./views/ModelView.vue');
const SemanticsView = () => import('./views/SemanticsView.vue');
import PlannedModuleView from './views/PlannedModuleView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录', public: true } },
    { path: '/account/password', component: PasswordView, meta: { title: '修改密码' } },
    { path: '/', component: FoundationView, meta: { title: '工程概览', anonymous: true } },
    { path: '/ask', component: PlannedModuleView, meta: { title: '智能问数', stage: 'M08', description: '先完成受控查询、业务语义和图表权限，再接入自然语言问数。' } },
    { path: '/charts/mine', component: ChartsView, meta: { title: '我的图表' } },
    { path: '/charts/new', component: ChartsView, meta: { title: '新建私有图表' } },
    { path: '/charts/shared', component: ChartsView, meta: { title: '共享给我' } },
    { path: '/charts/public', component: ChartsView, meta: { title: '全员共享' } },
    { path: '/charts/admin', component: ChartsView, meta: { title: '图表管理', admin: true } },
    { path: '/charts/trash', component: ChartsView, meta: { title: '图表回收站' } },
    { path: '/charts/:id', component: ChartsView, meta: { title: '图表详情' } },
    { path: '/sources', component: SourcesView, meta: { title: '数据源管理', admin: true } },
    { path: '/queries', component: QueryView, meta: { title: '安全 SQL 查询' } },
    { path: '/semantics', component: SemanticsView, meta: { title: '业务语义与指标' } },
    { path: '/subjects', component: SubjectsView, meta: { title: '业务主题' } },
    { path: '/model', component: ModelView, meta: { title: '模型配置', admin: true } },
    { path: '/settings', component: UsersView, meta: { title: '用户管理', admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});


router.beforeEach(async (to) => {
  if (to.meta.public) return true;
  try { await loadSession(); }
  catch (cause) {
    clearSession();
    session.error = cause instanceof ApiError && cause.status === 401 ? '请登录后继续' : errorMessage(cause);
    return to.meta.anonymous ? true : '/login';
  }
  if (to.meta.admin && session.user?.role !== 'SUPER_ADMIN') return '/';
  return true;
});
setUnauthorizedHandler(() => {
  clearSession();
  session.error = '登录已过期，请重新登录';
  if (router.currentRoute.value.path !== '/login') void router.replace('/login');
});
