<template>
  <div v-if="!isLoggedIn" class="login-page">
    <div class="bg-decoration"><div class="bg-circle bg-circle-1"></div><div class="bg-circle bg-circle-2"></div><div class="bg-circle bg-circle-3"></div></div>
    <div class="login-card">
      <div class="login-header"><div class="logo-icon">Y</div><h2>YOLO 训练管理系统</h2></div>
      <el-form @submit.prevent>
        <el-form-item><el-input v-model="loginForm.username" placeholder="用户名" size="large" prefix-icon="User" /></el-form-item>
        <el-form-item><el-input v-model="loginForm.password" type="password" placeholder="密码" size="large" prefix-icon="Lock" @keyup.enter="handleLogin" /></el-form-item>
        <el-button type="primary" @click="handleLogin" :loading="loginLoading" size="large" style="width:100%">登录</el-button>
      </el-form>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
    </div>
  </div>

  <div v-else class="app-layout">
    <div class="bg-decoration"><div class="bg-circle bg-circle-1"></div><div class="bg-circle bg-circle-2"></div><div class="bg-circle bg-circle-3"></div></div>

    <aside class="sidebar">
      <div class="sidebar-logo"><div class="logo-icon">Y</div><span class="logo-text">YOLO</span></div>
      <nav class="sidebar-nav">
        <div class="nav-item" :class="{active:activePage==='main'}" @click="activePage='main'"><el-icon><Monitor /></el-icon><span>训练管理</span></div>
        <div class="nav-item" :class="{active:activePage==='logs'}" @click="switchToLogs"><el-icon><Document /></el-icon><span>操作日志</span></div>
        <div class="nav-item" v-if="isAdmin" :class="{active:activePage==='users'}" @click="switchToUsers"><el-icon><UserFilled /></el-icon><span>用户管理</span></div>
      </nav>
      <div class="sidebar-footer">
        <div class="user-badge"><el-tag :type="currentUser.role==='ROOT'?'danger':currentUser.role==='ADMIN'?'warning':'info'" size="small">{{ currentUser.role }}</el-tag><span class="user-name">{{ currentUser.username }}</span></div>
        <div class="user-badge"><el-button size="small" @click="showChangePasswordDialog=true">更改密码</el-button></div>
        <div class="footer-actions"><el-button v-if="currentUser.role==='ROOT'" size="small" type="danger" plain @click="handleCleanup">清空</el-button><el-button size="small" type="danger" @click="handleLogout">退出</el-button></div>
      </div>
    </aside>

    <main class="main-content">
      <div v-show="activePage==='main'" class="page-main">
        <div class="page-header"><h2>训练管理</h2></div>
        <div class="top-section">
          <el-card shadow="hover" class="top-card dataset-card-top">
            <template #header><span class="card-title">添加数据集</span></template>
            <div class="input-row">
              <el-input v-model="form.inputDir" placeholder="请输入数据路径或拖拽文件夹到下方区域" @keyup.enter="handleAddDataset" size="large" clearable class="input-flex" />
              <el-button type="info" @click="browseFolder('请选择数据集文件夹','input')">浏览</el-button>
              <el-button type="primary" @click="handleAddDataset" :loading="currentStep==='addDataset'">添加</el-button>
            </div>
            <div class="drop-zone" :class="{'drop-zone-active':isDragOver}" @dragover.prevent="isDragOver=true" @dragleave.prevent="isDragOver=false" @drop.prevent="handleDrop">
              <div class="drop-zone-text"><el-icon size="28"><UploadFilled /></el-icon><span>拖拽文件夹到此处添加数据集（支持多个）</span></div>
            </div>
          </el-card>
          <el-card shadow="hover" class="top-card config-card-top">
            <template #header><span class="card-title">全局配置</span></template>
            <div class="config-row">
              <div class="cfg-item"><span class="cfg-label">同时训练</span><el-input-number v-model="maxConcurrentTasks" :min="1" :max="10" size="small" style="width:70px" /><el-button type="primary" size="small" @click="handleMaxConcurrentTasksChange(maxConcurrentTasks)">保存</el-button></div>
              <div class="cfg-item"><span class="cfg-label">Epochs</span><el-input-number v-model="defaultEpochs" :min="1" :max="1000" size="small" style="width:80px" /><el-button type="primary" size="small" @click="handleDefaultEpochsChange(defaultEpochs)">保存</el-button></div>
              <div class="cfg-item"><span class="cfg-label">Imgsz</span><el-input-number v-model="defaultImgsz" :min="32" :max="1280" :step="32" size="small" style="width:100px" /><el-button type="primary" size="small" @click="handleDefaultImgszChange(defaultImgsz)">保存</el-button></div>
            </div>
          </el-card>
        </div>

        <div class="datasets-section">
          <el-card shadow="hover" v-for="ds in pagedDatasets" :key="ds.name" class="dataset-card">
            <div class="dataset-header">
              <div class="dataset-info"><span class="dataset-name">{{ ds.name }}</span><el-tag size="small" type="info">{{ ds.createdBy }}</el-tag><el-tag :type="ds.preprocessed?'success':'info'" size="small">{{ ds.preprocessed?'已预处理':'未预处理' }}</el-tag><el-button v-if="!ds.preprocessed" size="small" type="warning" :loading="processingStatus[ds.name]" @click="handlePreprocessDataset(ds.name)">预处理</el-button></div>
              <div class="header-actions"><el-button size="small" type="primary" plain @click="addPendingRecord(ds.name)">+ 新增训练</el-button><el-button size="small" type="danger" @click="handleDeleteDataset(ds.name)">删除数据集</el-button></div>
            </div>
            <div class="dataset-records" style="overflow-x:auto">
              <table class="records-table"><thead><tr><th class="col-priority">优先级</th><th class="col-epoch">Epochs</th><th class="col-imgsz">Imgsz</th><th class="col-tstatus">训练状态</th><th class="col-estatus">测试状态</th><th class="col-action">操作</th></tr></thead>
                <tbody>
                  <tr v-for="rec in getDatasetRecords(ds.name)" :key="rec.recordName">
                    <td><el-input-number v-if="isEditable(rec)" v-model="rec.priority" :min="1" :max="10" size="small" style="width:72px" /><span v-else class="param-fixed">{{ rec.priority||5 }}</span></td>
                    <td><span class="param-fixed">{{ rec.epochs }}</span></td>
                    <td><span class="param-fixed">{{ rec.imgsz }}</span></td>
                    <td><div class="status-cell"><el-progress v-if="rec.trainStatus==='RUNNING'" :percentage="rec.trainProgress||0" :stroke-width="18" :text-inside="true" :format="p=>p+'%'" style="width:110px" /><el-tag v-else :type="getStatusClass(rec.trainStatus)" size="small">{{ getStatusText(rec.trainStatus,'train') }}</el-tag></div></td>
                    <td><el-tag :type="getStatusClass(rec.testStatus)" size="small">{{ getStatusText(rec.testStatus,'test') }}</el-tag></td>
                    <td><div class="record-actions">
                      <el-button v-if="isEditable(rec)" size="small" type="primary" :disabled="!ds.preprocessed" @click="handleTrainRecord(rec)">训练</el-button>
                      <el-button v-if="hasTrainLog(rec)" size="small" plain @click="handleViewTrainLog(rec)">训练日志</el-button>
                      <el-button v-if="rec.trainStatus==='COMPLETED'" size="small" type="success" @click="showSaveModelDialog(rec)">保存模型</el-button>
                      <el-button v-if="rec.trainStatus==='COMPLETED' && rec.testStatus!=='COMPLETED' && rec.testStatus!=='RUNNING'" size="small" type="success" @click="handleTestRecord(rec)">测试</el-button>
                      <el-button v-if="hasTestLog(rec)" size="small" plain @click="handleViewTestLog(rec)">测试日志</el-button>
                      <el-button size="small" type="danger" @click="handleDeleteRecord(rec)">删除</el-button>
                    </div></td>
                  </tr>
                  <tr v-for="pending in getPendingRecords(ds.name)" :key="pending.key">
                    <td><el-input-number v-model="pending.priority" :min="1" :max="10" size="small" style="width:72px" /></td>
                    <td><el-input-number v-model="pending.epochs" :min="1" :max="1000" size="small" style="width:72px" /></td>
                    <td><el-input-number v-model="pending.imgsz" :min="32" :max="1280" :step="32" size="small" style="width:90px" /></td>
                    <td><el-tag type="info" size="small">未训练</el-tag></td>
                    <td><el-tag type="info" size="small">未测试</el-tag></td>
                    <td><div class="record-actions"><el-button size="small" type="primary" :disabled="!ds.preprocessed" @click="handleTrainPending(ds.name,pending)">训练</el-button><el-button size="small" type="danger" @click="removePendingRecord(ds.name,pending.key)">删除</el-button></div></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </el-card>
          <div class="pagination-container"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" :page-sizes="[5,10,20,50]" layout="total,sizes,prev,pager,next" @size-change="handleSizeChange" @current-change="handleCurrentChange" small /></div>
        </div>

        <div class="logs-section" v-if="Object.keys(preprocessLogs).length">
          <el-card shadow="hover"><template #header><span class="card-title">预处理日志</span></template>
            <div class="log-grid"><div v-for="(log,name) in preprocessLogs" :key="'pre-'+name" class="log-item"><div class="log-header"><el-tag size="small">{{ name }}</el-tag><el-button type="danger" size="small" link @click="closePreprocessLog(name)">关闭</el-button></div><div class="log-container" :ref="el=>setLogRef(name,'preprocess',el)"><pre class="log-content">{{ log }}</pre></div></div></div>
          </el-card>
        </div>
        <div class="logs-section" v-if="Object.keys(trainLogs).length">
          <el-card shadow="hover"><template #header><span class="card-title">训练日志</span></template>
            <div class="log-grid"><div v-for="(log,name) in trainLogs" :key="'train-'+name" class="log-item"><div class="log-header"><el-tag size="small" type="warning">{{ name }}</el-tag><el-button type="danger" size="small" link @click="closeTrainLog(name)">关闭</el-button></div><div class="log-container" :ref="el=>setLogRef(name,'train',el)"><pre class="log-content">{{ log }}</pre></div></div></div>
          </el-card>
        </div>
        <div class="logs-section" v-if="Object.keys(testLogs).length">
          <el-card shadow="hover"><template #header><span class="card-title">测试日志</span></template>
            <div class="log-grid"><div v-for="(log,name) in testLogs" :key="'test-'+name" class="log-item"><div class="log-header"><el-tag size="small" type="success">{{ name }}</el-tag><el-button type="danger" size="small" link @click="closeTestLog(name)">关闭</el-button></div><div class="log-container" :ref="el=>setLogRef(name,'test',el)"><pre class="log-content">{{ log }}</pre></div></div></div>
          </el-card>
        </div>
      </div>

      <div v-show="activePage==='logs'" class="page-logs">
        <div class="page-header"><h2>操作日志</h2></div>
        <el-card shadow="hover" class="filter-card">
          <div class="filter-row">
            <div class="filter-item"><span class="filter-label">用户</span><el-input v-model="logFilter.username" placeholder="搜索用户名" clearable size="small" style="width:140px" /></div>
            <div class="filter-item"><span class="filter-label">操作</span><el-select v-model="logFilter.action" placeholder="全部" clearable size="small" style="width:150px"><el-option v-for="a in logActions" :key="a" :label="a" :value="a" /></el-select></div>
            <div class="filter-item"><span class="filter-label">起始</span><el-date-picker v-model="logFilter.startTime" type="datetime" placeholder="起始时间" size="small" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" style="width:200px" /></div>
            <div class="filter-item"><span class="filter-label">结束</span><el-date-picker v-model="logFilter.endTime" type="datetime" placeholder="结束时间" size="small" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" style="width:200px" /></div>
            <el-button type="primary" size="small" @click="fetchFilteredLogs">查询</el-button>
            <el-button size="small" @click="resetLogFilter">重置</el-button>
          </div>
        </el-card>
        <el-card shadow="hover" style="margin-top:14px">
          <el-table :data="pagedLogList" stripe size="small" class="fixed-table" max-height="600" :default-sort="{prop:'createdAt',order:'descending'}">
            <el-table-column prop="username" label="用户" width="100" /><el-table-column prop="action" label="操作" width="140"><template #default="{row}"><el-tag :type="getActionTagType(row.action)" size="small">{{ row.action }}</el-tag></template></el-table-column><el-table-column prop="target" label="目标" min-width="140" show-overflow-tooltip /><el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip /><el-table-column prop="createdAt" label="时间" width="170" sortable />
          </el-table>
          <div class="pagination-container" style="margin-top:12px"><el-pagination v-model:current-page="logPage" :page-size="logPageSize" :total="filteredLogs.length" :page-sizes="[20,50,100]" layout="total,sizes,prev,pager,next" @size-change="logPageSize=$event;logPage=1" @current-change="logPage=$event" small /></div>
        </el-card>
      </div>

      <div v-show="activePage==='users'" class="page-users">
        <div class="page-header"><h2>用户管理</h2></div>
        <el-card shadow="hover" class="filter-card">
          <div class="filter-row">
            <div class="filter-item"><span class="filter-label">搜索</span><el-input v-model="userSearch" placeholder="搜索用户名" clearable size="small" style="width:180px" @input="fetchFilteredUsers" /></div>
            <div class="filter-item"><span class="filter-label">角色</span><el-select v-model="userRoleFilter" placeholder="全部" clearable size="small" style="width:120px" @change="fetchFilteredUsers"><el-option label="ROOT" value="ROOT" /><el-option label="ADMIN" value="ADMIN" /><el-option label="USER" value="USER" /></el-select></div>
            <el-button type="primary" size="small" @click="showAddUserDialog=true">添加用户</el-button>
          </div>
        </el-card>
        <el-card shadow="hover" style="margin-top:14px">
          <el-table :data="filteredUserList" stripe size="small" class="fixed-table">
            <el-table-column prop="username" label="用户名" min-width="120" /><el-table-column prop="role" label="角色" min-width="100" align="center"><template #default="s"><el-tag :type="s.row.role==='ROOT'?'danger':s.row.role==='ADMIN'?'warning':'info'" size="small">{{ s.row.role }}</el-tag></template></el-table-column><el-table-column prop="createdAt" label="创建时间" min-width="170" /><el-table-column label="操作" min-width="180" align="center"><template #default="s"><el-button v-if="canEditUserRole(s.row)" size="small" @click="showRoleDialog(s.row)">修改角色</el-button><el-button v-if="s.row.role!=='ROOT'&&currentUser.role==='ROOT'" size="small" type="danger" @click="handleDeleteUser(s.row)">删除</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </div>
    </main>

    <el-dialog title="保存模型" v-model="saveModelDialogVisible" width="480px" :close-on-click-modal="false">
      <el-form :model="saveModelForm" label-width="80px">
        <el-form-item label="记录"><el-tag type="success">{{ saveModelForm.recordName }}</el-tag></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="saveModelForm.modelType"><el-radio label="best">最佳模型(best.pt)</el-radio><el-radio label="last">最后模型(last.pt)</el-radio></el-radio-group></el-form-item>
        <el-form-item label="保存路径"><div class="save-path-row"><el-input v-model="saveModelForm.savePath" placeholder="默认项目saved_models目录" /><el-button type="info" @click="browseFolder('请选择模型保存路径','save')">浏览</el-button></div></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="confirmSaveModel" :loading="saveModelLoading">保存</el-button></template>
    </el-dialog>

    <el-dialog title="添加用户" v-model="showAddUserDialog" width="360px"><el-form :model="newUserForm" label-width="70px"><el-form-item label="用户名"><el-input v-model="newUserForm.username" /></el-form-item><el-form-item label="密码"><el-input v-model="newUserForm.password" type="password" /></el-form-item><el-form-item v-if="currentUser.role==='ROOT'" label="角色"><el-select v-model="newUserForm.role"><el-option label="普通用户" value="USER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item><el-form-item v-else label="角色"><el-tag type="info">普通用户</el-tag></el-form-item></el-form><template #footer><el-button @click="showAddUserDialog=false">取消</el-button><el-button type="primary" @click="handleAddUser">创建</el-button></template></el-dialog>

    <el-dialog title="修改角色" v-model="showRoleDialogVisible" width="320px">
      <el-form label-width="70px"><el-form-item label="用户"><strong>{{ roleEditTarget.username }}</strong></el-form-item><el-form-item label="新角色"><el-select v-model="roleEditNewRole"><el-option v-if="currentUser.role==='ROOT'" label="普通用户(USER)" value="USER" /><el-option v-if="currentUser.role==='ROOT'" label="管理员(ADMIN)" value="ADMIN" /><el-option v-if="currentUser.role==='ADMIN'" label="普通用户(USER)" value="USER" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="showRoleDialogVisible=false">取消</el-button><el-button type="primary" @click="confirmRoleChange">确认</el-button></template>
    </el-dialog>

    <el-dialog title="确认删除数据集" v-model="deleteConfirmVisible" width="360px"><p>确定删除数据集 <strong>{{ datasetToDelete }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除所有关联的训练记录、K8s Job/Pod、YAML文件、runs目录和日志文件。</p><template #footer><el-button @click="deleteConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDelete">确认</el-button></template></el-dialog>

    <el-dialog title="确认删除训练记录" v-model="deleteRecordConfirmVisible" width="360px"><p>确定删除训练记录 <strong>{{ recordToDelete }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除关联的K8s Job/Pod、YAML文件、runs目录和日志文件。</p><template #footer><el-button @click="deleteRecordConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDeleteRecord">确认</el-button></template></el-dialog>

    <el-dialog title="确认清空" v-model="cleanupConfirmVisible" width="400px"><p>确定<strong>清空全部数据</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除所有数据集、训练记录、K8s Job/YAML和操作日志，不可恢复。</p><template #footer><el-button @click="cleanupConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmCleanup" :loading="cleanupLoading">确认</el-button></template></el-dialog>

    <el-dialog title="确认删除用户" v-model="deleteUserConfirmVisible" width="360px"><p>确定删除用户 <strong>{{ userToDelete.username }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作不可恢复。</p><template #footer><el-button @click="deleteUserConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDeleteUser">确认</el-button></template></el-dialog>

    <el-dialog title="修改密码" v-model="showChangePasswordDialog" width="360px"><el-form :model="changePasswordForm" label-width="70px"><el-form-item label="旧密码"><el-input v-model="changePasswordForm.oldPassword" type="password" placeholder="请输入旧密码" /></el-form-item><el-form-item label="新密码"><el-input v-model="changePasswordForm.newPassword" type="password" placeholder="请输入新密码（至少4位）" /></el-form-item><el-form-item label="确认"><el-input v-model="changePasswordForm.confirmPassword" type="password" placeholder="再次输入新密码" /></el-form-item></el-form><template #footer><el-button @click="showChangePasswordDialog=false">取消</el-button><el-button type="primary" @click="handleChangePassword">确认修改</el-button></template></el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, computed, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Monitor, Document, UserFilled } from '@element-plus/icons-vue'
import axios from 'axios'

const isLoggedIn=ref(false)
const loginForm=reactive({username:'',password:''})
const loginLoading=ref(false),loginError=ref('')
const currentUser=reactive({username:'',role:''})
const token=ref('')
const form=reactive({inputDir:''})
const maxConcurrentTasks=ref(2),savedMaxConcurrentTasks=ref(2),defaultEpochs=ref(2),savedDefaultEpochs=ref(2),defaultImgsz=ref(640),savedDefaultImgsz=ref(640)
const currentStep=ref(''),datasetList=ref([]),deleteConfirmVisible=ref(false),cleanupConfirmVisible=ref(false),cleanupLoading=ref(false),datasetToDelete=ref('')
const deleteRecordConfirmVisible=ref(false),recordToDelete=ref('')
const processingStatus=ref({}),testLoadingStatus=ref({}),trainingRecords=ref([]),userList=ref([]),operationLogs=ref([]),showAddUserDialog=ref(false)
const newUserForm=reactive({username:'',password:'',role:'USER'})
const pendingRecords=ref({}),preprocessLogs=ref({}),trainLogs=ref({}),testLogs=ref({})
const logRefs=ref({})
const saveModelDialogVisible=ref(false),saveModelForm=reactive({dataName:'',modelType:'best',savePath:'',recordName:''}),saveModelLoading=ref(false)
const showRoleDialogVisible=ref(false),roleEditTarget=ref({}),roleEditNewRole=ref('')
const deleteUserConfirmVisible=ref(false),userToDelete=ref({})
const showChangePasswordDialog=ref(false),changePasswordForm=reactive({oldPassword:'',newPassword:'',confirmPassword:''})
const currentPage=ref(1),pageSize=ref(10),total=ref(0)
const isDragOver=ref(false)
const activePage=ref('main')
const preprocessTimers=ref({})

const logFilter=reactive({username:'',action:'',startTime:'',endTime:''})
const logActions=['ADD_DATASET','PREPROCESS','TRAIN','TEST','SAVE_MODEL','DELETE_DATASET','DELETE_RECORD','CONFIG_CHANGE','CREATE_USER','UPDATE_ROLE','DELETE_USER','CHANGE_PASSWORD']
const logPage=ref(1),logPageSize=ref(20)
const userSearch=ref(''),userRoleFilter=ref('')

const wsConnections=new Map()
let statusRefreshTimer=null

const isAdmin=computed(()=>currentUser.role==='ROOT'||currentUser.role==='ADMIN')
const pagedDatasets=computed(()=>{const s=(currentPage.value-1)*pageSize.value;return datasetList.value.slice(s,s+pageSize.value)})
const api=computed(()=>axios.create({headers:{Authorization:`Bearer ${token.value}`}}))
const showMsg=(msg,type='success')=>ElMessage({message:msg,type,duration:3000,center:true})

const filteredLogs=computed(()=>{let list=[...operationLogs.value];if(logFilter.username){const kw=logFilter.username.toLowerCase();list=list.filter(l=>l.username&&l.username.toLowerCase().includes(kw))}if(logFilter.action){list=list.filter(l=>l.action===logFilter.action)}if(logFilter.startTime){try{const s=new Date(logFilter.startTime);list=list.filter(l=>l.createdAt&&new Date(l.createdAt)>=s)}catch(e){}}if(logFilter.endTime){try{const e=new Date(logFilter.endTime);list=list.filter(l=>l.createdAt&&new Date(l.createdAt)<=e)}catch(e){}}return list})
const pagedLogList=computed(()=>{const s=(logPage.value-1)*logPageSize.value;return filteredLogs.value.slice(s,s+logPageSize.value)})
const filteredUserList=computed(()=>{let list=[...userList.value];if(userSearch.value){const kw=userSearch.value.toLowerCase();list=list.filter(u=>u.username.toLowerCase().includes(kw))}if(userRoleFilter.value){list=list.filter(u=>u.role===userRoleFilter.value)}return list})

const looksLikeFullDatasetPath=(p)=>{if(!p||typeof p!=='string')return false;const t=p.trim();if(t.length<2)return false;if(/^[a-zA-Z]:[\\/]/.test(t))return t.length>3;if(t.startsWith('\\\\'))return t.split(/[\\/]/).filter(Boolean).length>=2;if(t.startsWith('/'))return t.split('/').filter(Boolean).length>=2;if(t.includes('\\'))return t.split(/[\\/]/).filter(Boolean).length>=2;if(t.includes('/'))return t.split('/').filter(Boolean).length>=2;return false}
const canEditUserRole=(row)=>{if(!row||row.role==='ROOT')return false;if(currentUser.username===row.username)return false;if(currentUser.role==='ROOT')return true;if(currentUser.role==='ADMIN'&&row.role==='USER')return true;return false}
const getDatasetRecords=(dataName)=>trainingRecords.value.filter(r=>r.dataName===dataName)
const getPendingRecords=(dataName)=>pendingRecords.value[dataName]||[]
const isEditable=(rec)=>rec.trainStatus==='IDLE'&&(!rec.trainJobId||rec.trainJobId==='')
const hasTrainLog=(rec)=>rec.trainStatus!=='IDLE'||rec.trainJobId
const hasTestLog=(rec)=>rec.testStatus&&rec.testStatus!=='IDLE'
const addPendingRecord=(dataName)=>{if(!pendingRecords.value[dataName])pendingRecords.value[dataName]=[];const k='pending-'+Date.now();pendingRecords.value[dataName].push({key:k,epochs:savedDefaultEpochs.value,imgsz:savedDefaultImgsz.value,priority:5})}
const removePendingRecord=(dataName,key)=>{if(pendingRecords.value[dataName])pendingRecords.value[dataName]=pendingRecords.value[dataName].filter(r=>r.key!==key)}

const switchToLogs=()=>{activePage.value='logs';loadOperationLogs()}
const switchToUsers=()=>{activePage.value='users';loadUsers()}

const handleLogin=async()=>{if(loginLoading.value)return;loginLoading.value=true;loginError.value='';try{const r=await axios.post('/api/auth/login',loginForm);token.value=r.data.token;currentUser.username=r.data.username;currentUser.role=r.data.role;isLoggedIn.value=true;localStorage.setItem('token',token.value);localStorage.setItem('username',currentUser.username);localStorage.setItem('role',currentUser.role);onLoginSuccess()}catch(e){loginError.value=e.response?.data?.message||'登录失败'}finally{loginLoading.value=false}}

const handleLogout=()=>{token.value='';currentUser.username='';currentUser.role='';isLoggedIn.value=false;localStorage.removeItem('token');localStorage.removeItem('username');localStorage.removeItem('role');stopAllWs();stopStatusRefresh();stopAllPreprocessTimers();datasetList.value=[];trainingRecords.value=[];userList.value=[];operationLogs.value=[];Object.keys(pendingRecords.value).forEach(k=>delete pendingRecords.value[k]);Object.keys(preprocessLogs.value).forEach(k=>delete preprocessLogs.value[k]);Object.keys(trainLogs.value).forEach(k=>delete trainLogs.value[k]);Object.keys(testLogs.value).forEach(k=>delete testLogs.value[k]);Object.keys(processingStatus.value).forEach(k=>delete processingStatus.value[k]);Object.keys(testLoadingStatus.value).forEach(k=>delete testLoadingStatus.value[k]);currentPage.value=1;total.value=0;activePage.value='main'}

const onLoginSuccess=async()=>{try{await Promise.all([refreshDatasets(),loadTrainingRecords(),loadOperationLogs()]);if(isAdmin.value)await loadUsers();const r=await api.value.get('/api/scheduler/config');if(r.data){maxConcurrentTasks.value=r.data.maxConcurrentTasks;savedMaxConcurrentTasks.value=r.data.maxConcurrentTasks;defaultEpochs.value=r.data.defaultEpochs;savedDefaultEpochs.value=r.data.defaultEpochs;defaultImgsz.value=r.data.defaultImgsz;savedDefaultImgsz.value=r.data.defaultImgsz}startStatusRefresh()}catch(e){}}

const loadTrainingRecords=async()=>{try{const oldRecords=[...trainingRecords.value];trainingRecords.value=(await api.value.get('/api/training-records')).data;checkStatusChanges(oldRecords,trainingRecords.value)}catch(e){}}
const loadUsers=async()=>{try{const params={};if(userSearch.value)params.search=userSearch.value;if(userRoleFilter.value)params.role=userRoleFilter.value;userList.value=(await api.value.get('/api/users',{params})).data}catch(e){}}
const loadOperationLogs=async()=>{try{const params={};if(logFilter.username)params.username=logFilter.username;if(logFilter.action)params.action=logFilter.action;if(logFilter.startTime)params.startTime=logFilter.startTime;if(logFilter.endTime)params.endTime=logFilter.endTime;operationLogs.value=(await api.value.get('/api/users/logs',{params})).data}catch(e){}}
const refreshDatasets=async()=>{try{const r=await api.value.get('/api/datasets');datasetList.value=r.data;total.value=r.data.length}catch(e){if(e.response?.status===401)handleLogout()}}

const fetchFilteredLogs=async()=>{logPage.value=1;await loadOperationLogs()}
const resetLogFilter=()=>{logFilter.username='';logFilter.action='';logFilter.startTime='';logFilter.endTime='';logPage.value=1;loadOperationLogs()}
const fetchFilteredUsers=async()=>{await loadUsers()}

const getActionTagType=(action)=>{const map={ADD_DATASET:'success',PREPROCESS:'warning',TRAIN:'primary',TEST:'primary',SAVE_MODEL:'success',DELETE_DATASET:'danger',DELETE_RECORD:'danger',CONFIG_CHANGE:'info',CREATE_USER:'success',UPDATE_ROLE:'warning',DELETE_USER:'danger',CHANGE_PASSWORD:'warning'};return map[action]||'info'}

const handleAddDataset=async()=>{if(!form.inputDir){showMsg('请输入数据路径','warning');return}const id=form.inputDir.trim().replace(/^["']|["']$/g,'');if(!looksLikeFullDatasetPath(id)){showMsg('路径无效：请填写完整路径','warning');return}const dn=id.split(/[/\\]/).pop();currentStep.value='addDataset';try{await api.value.post('/api/datasets',{inputDir:id});await refreshDatasets();await loadTrainingRecords();showMsg(`数据集 ${dn} 添加成功`)}catch(e){showMsg(`失败: ${e.response?.data?.message||e.message}`,'error')}finally{currentStep.value='';form.inputDir=''}}

const handleDrop=async(e)=>{isDragOver.value=false;const files=e.dataTransfer.files;if(!files||files.length===0)return;const dirs=[];for(let i=0;i<files.length;i++){const f=files[i];const fp=f.path||f.name;if(fp&&!dirs.includes(fp))dirs.push(fp)}const ok=dirs.filter(looksLikeFullDatasetPath);const bad=dirs.filter(d=>!looksLikeFullDatasetPath(d));if(ok.length===0){showMsg(bad.length?'无法识别完整路径，请使用浏览或手动粘贴':'请拖拽文件夹','warning');return}if(bad.length)showMsg(`已跳过 ${bad.length} 个无效路径`,'warning');currentStep.value='addDataset';try{const r=await api.value.post('/api/datasets/batch',{dirs:ok});const d=r.data;await refreshDatasets();await loadTrainingRecords();showMsg(`成功添加 ${d.added} 个数据集${d.skipped>0?'，跳过 '+d.skipped+' 个已存在':''}`)}catch(e){showMsg(`批量添加失败: ${e.response?.data?.message||e.message}`,'error')}finally{currentStep.value=''}}

const browseFolder=async(title,target)=>{try{const r=await axios.get('/api/dialog/folder',{params:{title},headers:{Authorization:`Bearer ${token.value}`}});if(r.data.status==='success'&&r.data.path){if(target==='input')form.inputDir=r.data.path;else if(target==='save')saveModelForm.savePath=r.data.path}}catch(e){showMsg('打开文件夹失败','error')}}

const handlePreprocessDataset=async(dataName)=>{processingStatus.value[dataName]=true;preprocessLogs.value[dataName]=`正在预处理 ${dataName}...\n`;try{const r=await api.value.post('/api/preprocess',{dataName});showMsg('预处理任务已启动');pollPreprocess(r.data.jobId,dataName)}catch(e){preprocessLogs.value[dataName]+=`错误: ${e.message}\n`;showMsg('预处理失败','error');processingStatus.value[dataName]=false}}

const pollPreprocess=(jobId,dataName)=>{const tid=`pre-${dataName}-${Date.now()}`;const iv=setInterval(async()=>{try{const r=await api.value.get(`/api/status/${jobId}`);if(r.data){if(r.data.log)preprocessLogs.value[dataName]=r.data.log;if(r.data.status==='COMPLETED'||r.data.status==='DONE'||r.data.status==='FAILED'){clearInterval(iv);delete preprocessTimers.value[tid];processingStatus.value[dataName]=false;if(r.data.status==='COMPLETED'||r.data.status==='DONE')showMsg(`数据集 ${dataName} 预处理成功`);else showMsg(`预处理失败`,'error');refreshDatasets()}}}catch(e){clearInterval(iv);delete preprocessTimers.value[tid];processingStatus.value[dataName]=false}},1000);preprocessTimers.value[tid]=iv}

const stopAllPreprocessTimers=()=>{Object.values(preprocessTimers.value).forEach(id=>clearInterval(id));preprocessTimers.value={}}

const handleTrainRecord=async(rec)=>{const rn=rec.recordName;trainLogs.value[rn]='';try{const r=await api.value.post('/api/scheduler/add',{dataName:rec.dataName,epochs:rec.epochs,imgsz:rec.imgsz,priority:rec.priority||5});showMsg(r.data.message);await loadTrainingRecords();connectLogWebSocket(rn,'train');startStatusRefresh()}catch(e){delete trainLogs.value[rn];showMsg(e.response?.data?.message||'加入队列失败','error')}}

const handleTrainPending=async(dataName,pending)=>{const rn=`${dataName}-e${pending.epochs}-i${pending.imgsz}`;trainLogs.value[rn]='';try{const r=await api.value.post('/api/scheduler/add',{dataName,epochs:pending.epochs,imgsz:pending.imgsz,priority:pending.priority});showMsg(r.data.message);removePendingRecord(dataName,pending.key);await loadTrainingRecords();connectLogWebSocket(rn,'train');startStatusRefresh()}catch(e){delete trainLogs.value[rn];showMsg(e.response?.data?.message||'加入队列失败','error')}}

const handleTestRecord=async(rec)=>{testLogs.value[rec.recordName]='';testLoadingStatus.value[rec.recordName]=true;try{const r=await api.value.post('/api/test',{dataName:rec.dataName,imgsz:rec.imgsz,recordName:rec.recordName});showMsg('测试任务已启动');await loadTrainingRecords();connectLogWebSocket(rec.recordName,'test');startStatusRefresh()}catch(e){testLogs.value[rec.recordName]=`错误: ${e.message}\n`;testLoadingStatus.value[rec.recordName]=false}}

const connectLogWebSocket=(recordName,type)=>{const logKey=recordName+'-'+type;if(wsConnections.has(logKey)){const old=wsConnections.get(logKey);if(old.readyState===WebSocket.OPEN||old.readyState===WebSocket.CONNECTING)return}const wsUrl=`ws://${window.location.host}/ws/logs/${recordName}/${type}`;const ws=new WebSocket(wsUrl);ws.onopen=()=>{console.log(`WebSocket connected: ${logKey}`)};ws.onmessage=(event)=>{if(type==='train'){trainLogs.value[recordName]=(trainLogs.value[recordName]||'')+event.data}else if(type==='test'){testLogs.value[recordName]=(testLogs.value[recordName]||'')+event.data}autoScroll(recordName,type)};ws.onclose=()=>{wsConnections.delete(logKey);refreshDatasets();loadTrainingRecords()};ws.onerror=()=>{wsConnections.delete(logKey)};wsConnections.set(logKey,ws)}

const handleViewTrainLog=async(rec)=>{const rn=rec.recordName;try{if(rec.trainStatus==='RUNNING'||rec.trainStatus==='QUEUED'){connectLogWebSocket(rn,'train')}else{const r=await api.value.get(`/api/training-records/${rn}/train-log`);if(r.data.log)trainLogs.value[rn]=r.data.log;scrollToBottomNow(rn,'train')}}catch(e){trainLogs.value[rn]=`获取日志失败: ${e.message}`}}

const handleViewTestLog=async(rec)=>{const rn=rec.recordName;try{if(rec.testStatus==='RUNNING'||rec.testStatus==='QUEUED'){connectLogWebSocket(rn,'test')}else{const r=await api.value.get(`/api/training-records/${rn}/test-log`);if(r.data.log)testLogs.value[rn]=r.data.log;scrollToBottomNow(rn,'test')}}catch(e){testLogs.value[rn]=`获取日志失败: ${e.message}`}}

const closeTrainLog=n=>{delete trainLogs.value[n];closeWsConnection(n+'-train')}
const closeTestLog=n=>{delete testLogs.value[n];closeWsConnection(n+'-test')}
const closePreprocessLog=n=>{delete preprocessLogs.value[n]}

const closeWsConnection=key=>{const ws=wsConnections.get(key);if(ws){if(ws.readyState===WebSocket.OPEN||ws.readyState===WebSocket.CONNECTING)ws.close();wsConnections.delete(key)}}
const stopAllWs=()=>{wsConnections.forEach(ws=>{if(ws.readyState===WebSocket.OPEN||ws.readyState===WebSocket.CONNECTING)ws.close()});wsConnections.clear()}

const checkStatusChanges=(oldRecords,newRecords)=>{for(const rec of newRecords){const old=oldRecords.find(r=>r.recordName===rec.recordName);const trainLogOpen=rec.recordName in trainLogs.value;const testLogOpen=rec.recordName in testLogs.value;if(trainLogOpen){if(old&&old.trainStatus!=='RUNNING'&&rec.trainStatus==='RUNNING'){connectLogWebSocket(rec.recordName,'train')}if(old&&old.trainStatus==='RUNNING'&&rec.trainStatus==='COMPLETED'){closeWsConnection(rec.recordName+'-train');showMsg(`${rec.recordName} 训练完成`)}if(old&&old.trainStatus==='RUNNING'&&rec.trainStatus==='FAILED'){closeWsConnection(rec.recordName+'-train');showMsg(`${rec.recordName} 训练失败`,'error')}}if(testLogOpen){if(old&&old.testStatus!=='RUNNING'&&rec.testStatus==='RUNNING'){connectLogWebSocket(rec.recordName,'test')}if(old&&old.testStatus==='RUNNING'&&rec.testStatus==='COMPLETED'){closeWsConnection(rec.recordName+'-test');showMsg(`${rec.recordName} 测试完成`)}if(old&&old.testStatus==='RUNNING'&&rec.testStatus==='FAILED'){closeWsConnection(rec.recordName+'-test');showMsg(`${rec.recordName} 测试失败`,'error')}}}}

const hasActiveTasks=()=>trainingRecords.value.some(r=>r.trainStatus==='RUNNING'||r.trainStatus==='QUEUED'||r.testStatus==='RUNNING'||r.testStatus==='QUEUED')

const startStatusRefresh=()=>{if(statusRefreshTimer)return;statusRefreshTimer=setInterval(async()=>{if(!hasActiveTasks()&&Object.keys(preprocessLogs.value).length===0){stopStatusRefresh();return}await loadTrainingRecords()},3000)}
const stopStatusRefresh=()=>{if(statusRefreshTimer){clearInterval(statusRefreshTimer);statusRefreshTimer=null}}

const handleDeleteRecord=(rec)=>{recordToDelete.value=rec.recordName;deleteRecordConfirmVisible.value=true}
const confirmDeleteRecord=async()=>{const rn=recordToDelete.value;try{await api.value.delete(`/api/training-records/${encodeURIComponent(rn)}`);delete trainLogs.value[rn];delete testLogs.value[rn];closeWsConnection(rn+'-train');closeWsConnection(rn+'-test');showMsg('训练记录已删除');await refreshDatasets();await loadTrainingRecords();deleteRecordConfirmVisible.value=false}catch(e){showMsg(e.response?.data?.message||(e.response?.status===404?'记录不存在':'删除失败'),'error');deleteRecordConfirmVisible.value=false}}

const showSaveModelDialog=rec=>{saveModelForm.dataName=rec.dataName;saveModelForm.recordName=rec.recordName;saveModelForm.modelType='best';saveModelForm.savePath='';saveModelDialogVisible.value=true}
const confirmSaveModel=async()=>{saveModelLoading.value=true;try{const r=await api.value.post('/api/model/save',{dataName:saveModelForm.dataName,modelType:saveModelForm.modelType,savePath:saveModelForm.savePath,recordName:saveModelForm.recordName});if(r.data.status==='success'){showMsg('模型保存成功');saveModelDialogVisible.value=false}}catch(e){showMsg(e.response?.data?.message||'保存失败','error')}finally{saveModelLoading.value=false}}

const handleDeleteDataset=dn=>{datasetToDelete.value=dn;deleteConfirmVisible.value=true}
const confirmDelete=async()=>{try{const dn=datasetToDelete.value;await api.value.delete(`/api/datasets/${encodeURIComponent(dn)}`);const recs=trainingRecords.value.filter(r=>r.dataName===dn);recs.forEach(r=>{delete trainLogs.value[r.recordName];delete testLogs.value[r.recordName];closeWsConnection(r.recordName+'-train');closeWsConnection(r.recordName+'-test')});delete preprocessLogs.value[dn];delete processingStatus.value[dn];await refreshDatasets();await loadTrainingRecords();showMsg('数据集及关联资源已删除');deleteConfirmVisible.value=false}catch(e){showMsg(e.response?.data?.message||(e.response?.status===404?'数据集不存在':'删除失败'),'error')}}

const handleCleanup=()=>{cleanupConfirmVisible.value=true}
const confirmCleanup=async()=>{cleanupLoading.value=true;try{await api.value.post('/api/cleanup');await refreshDatasets();await loadTrainingRecords();await loadOperationLogs();showMsg('系统已重置');stopAllWs();Object.keys(preprocessLogs.value).forEach(k=>delete preprocessLogs.value[k]);Object.keys(trainLogs.value).forEach(k=>delete trainLogs.value[k]);Object.keys(testLogs.value).forEach(k=>delete testLogs.value[k])}catch(e){showMsg(e.response?.data?.message||'清理失败','error')}finally{cleanupLoading.value=false;cleanupConfirmVisible.value=false}}

const handleMaxConcurrentTasksChange=async(v)=>{try{await api.value.post('/api/scheduler/max-concurrent',{max:v});savedMaxConcurrentTasks.value=v;showMsg(`同时训练数已设为 ${v}`)}catch(e){showMsg('更新失败','error')}}
const handleDefaultEpochsChange=async(v)=>{try{await api.value.post('/api/scheduler/default-epochs',{epochs:v});savedDefaultEpochs.value=v;showMsg(`默认Epochs已设为 ${v}`)}catch(e){showMsg('更新失败','error')}}
const handleDefaultImgszChange=async(v)=>{try{await api.value.post('/api/scheduler/default-imgsz',{imgsz:v});savedDefaultImgsz.value=v;showMsg(`默认Imgsz已设为 ${v}`)}catch(e){showMsg('更新失败','error')}}

const handleAddUser=async()=>{if(currentUser.role==='ADMIN')newUserForm.role='USER';try{await api.value.post('/api/auth/register',newUserForm);showAddUserDialog.value=false;newUserForm.username='';newUserForm.password='';newUserForm.role='USER';loadUsers();showMsg('用户创建成功')}catch(e){showMsg(e.response?.data?.message||'创建失败','error')}}
const showRoleDialog=user=>{roleEditTarget.value=user;roleEditNewRole.value=currentUser.role==='ADMIN'?'USER':(user.role||'USER');showRoleDialogVisible.value=true}
const confirmRoleChange=async()=>{try{await api.value.put(`/api/users/${roleEditTarget.value.id}/role`,{role:roleEditNewRole.value});loadUsers();showRoleDialogVisible.value=false;showMsg(`角色已修改为 ${roleEditNewRole.value}`)}catch(e){showMsg(e.response?.data?.message||'修改失败','error')}}
const handleDeleteUser=(user)=>{userToDelete.value=user;deleteUserConfirmVisible.value=true}
const confirmDeleteUser=async()=>{try{await api.value.delete(`/api/users/${userToDelete.value.id}`);deleteUserConfirmVisible.value=false;loadUsers();showMsg('用户已删除')}catch(e){showMsg(e.response?.data?.message||'删除失败','error')}}
const handleChangePassword=async()=>{if(!changePasswordForm.oldPassword||!changePasswordForm.newPassword){showMsg('请填写完整','warning');return}if(changePasswordForm.newPassword!==changePasswordForm.confirmPassword){showMsg('两次密码不一致','warning');return}if(changePasswordForm.newPassword.length<4){showMsg('新密码至少4位','warning');return}try{await api.value.put('/api/users/change-password',{oldPassword:changePasswordForm.oldPassword,newPassword:changePasswordForm.newPassword});showMsg('密码修改成功');showChangePasswordDialog.value=false;changePasswordForm.oldPassword='';changePasswordForm.newPassword='';changePasswordForm.confirmPassword=''}catch(e){showMsg(e.response?.data?.message||'修改失败','error')}}
const handleSizeChange=v=>{pageSize.value=v;currentPage.value=1}
const handleCurrentChange=v=>{currentPage.value=v}
const getStatusClass=s=>{if(s==='RUNNING')return'warning';if(s==='QUEUED')return'primary';if(s==='COMPLETED')return'success';if(s==='FAILED')return'danger';return'info'}
const getStatusText=(s,t)=>{const p=t==='train'?'训练':'测试';if(s==='RUNNING')return p+'中';if(s==='QUEUED')return'排队中';if(s==='COMPLETED')return'已完成';if(s==='FAILED')return'失败';return t==='train'?'未训练':'未测试'}

const setLogRef=(n,t,el)=>{if(el)logRefs.value[`${n}-${t}`]=el}
const autoScroll=(n,t)=>{nextTick(()=>{const el=logRefs.value[`${n}-${t}`];if(el){const distanceToBottom=el.scrollHeight-el.scrollTop-el.clientHeight;if(distanceToBottom<=100)el.scrollTop=el.scrollHeight}})}
const scrollToBottomNow=(n,t)=>{nextTick(()=>{const el=logRefs.value[`${n}-${t}`];if(el)el.scrollTop=el.scrollHeight})}
watch(trainLogs,()=>Object.keys(trainLogs.value).forEach(n=>autoScroll(n,'train')),{deep:true})
watch(testLogs,()=>Object.keys(testLogs.value).forEach(n=>autoScroll(n,'test')),{deep:true})
watch(preprocessLogs,()=>Object.keys(preprocessLogs.value).forEach(n=>autoScroll(n,'preprocess')),{deep:true})

onMounted(()=>{const t=localStorage.getItem('token'),u=localStorage.getItem('username'),r=localStorage.getItem('role');if(t&&u){token.value=t;currentUser.username=u;currentUser.role=r||'USER';isLoggedIn.value=true;onLoginSuccess()}})
onUnmounted(()=>{stopAllWs();stopStatusRefresh();stopAllPreprocessTimers()})
</script>

<style scoped>
.login-page{min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#0f0c29,#302b63,#24243e);position:relative}
.login-card{background:rgba(255,255,255,.95);border-radius:16px;padding:40px;width:380px;box-shadow:0 20px 60px rgba(0,0,0,.3);z-index:1}
.login-header{text-align:center;margin-bottom:30px}.login-header h2{margin:12px 0 0;color:#303133}
.login-error{color:#f56c6c;text-align:center;margin-top:12px;font-size:13px}

.app-layout{display:flex;min-height:100vh;background:#f0f2f5;position:relative}
.bg-decoration{position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:0;overflow:hidden}
.bg-circle{position:absolute;border-radius:50%;opacity:.06}.bg-circle-1{width:600px;height:600px;background:#409eff;top:-200px;right:-100px}.bg-circle-2{width:400px;height:400px;background:#67c23a;bottom:-100px;left:-80px}.bg-circle-3{width:300px;height:300px;background:#e6a23c;top:50%;left:50%;transform:translate(-50%,-50%)}

.sidebar{width:200px;background:linear-gradient(180deg,#1a1a2e,#16213e);color:#fff;display:flex;flex-direction:column;position:fixed;top:0;left:0;bottom:0;z-index:10;box-shadow:2px 0 12px rgba(0,0,0,.15)}
.sidebar-logo{display:flex;align-items:center;gap:10px;padding:20px 18px;border-bottom:1px solid rgba(255,255,255,.1)}
.logo-icon{width:36px;height:36px;background:linear-gradient(135deg,#409eff,#67c23a);border-radius:8px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px;font-weight:800;flex-shrink:0}
.logo-text{font-size:18px;font-weight:700;letter-spacing:1px}
.sidebar-nav{flex:1;padding:12px 0}
.nav-item{display:flex;align-items:center;gap:10px;padding:12px 20px;cursor:pointer;transition:all .2s;color:rgba(255,255,255,.65);font-size:14px;border-left:3px solid transparent}
.nav-item:hover{background:rgba(255,255,255,.08);color:#fff}
.nav-item.active{background:rgba(64,158,255,.15);color:#409eff;border-left-color:#409eff}
.sidebar-footer{padding:14px 16px;border-top:1px solid rgba(255,255,255,.1)}
.user-badge{display:flex;align-items:center;gap:6px;margin-bottom:8px}.user-name{font-size:13px;color:rgba(255,255,255,.8)}
.footer-actions{display:flex;gap:6px}

.main-content{margin-left:200px;flex:1;padding:20px 28px;position:relative;z-index:1;min-height:100vh}
.page-header{margin-bottom:18px}.page-header h2{margin:0;font-size:20px;color:#303133;font-weight:700}

.top-section{display:flex;gap:14px;margin-bottom:16px}.top-card{flex:1;min-width:0}
.dataset-card-top{flex:3}.config-card-top{flex:2}
.card-title{font-size:15px;font-weight:600;color:#303133}
.input-row{display:flex;align-items:center;gap:10px}.input-flex{flex:1}
.config-row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.cfg-item{display:flex;align-items:center;gap:5px}.cfg-label{font-size:12px;color:#606266;white-space:nowrap}

.drop-zone{margin-top:10px;border:2px dashed #c0c4cc;border-radius:8px;padding:16px;text-align:center;transition:all .3s;cursor:pointer}
.drop-zone:hover{border-color:#409eff;background:rgba(64,158,255,.04)}
.drop-zone-active{border-color:#409eff;background:rgba(64,158,255,.08)}
.drop-zone-text{display:flex;align-items:center;justify-content:center;gap:8px;color:#909399;font-size:13px}

.datasets-section{margin-bottom:16px}.dataset-card{margin-bottom:12px}
.dataset-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;padding-bottom:8px;border-bottom:1px solid #ebeef5;flex-wrap:wrap;gap:8px}
.dataset-info{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.dataset-name{font-size:15px;font-weight:700;color:#303133}
.header-actions{display:flex;align-items:center;gap:6px;flex-shrink:0}

.records-table{width:100%;border-collapse:collapse;font-size:13px;table-layout:auto}
.records-table th{background:#f5f7fa;padding:7px 4px;text-align:center;font-weight:600;color:#606266;border:1px solid #ebeef5;white-space:nowrap}
.records-table td{padding:6px 4px;text-align:center;vertical-align:middle;border:1px solid #ebeef5}
.col-priority{width:90px}.col-epoch{width:80px}.col-imgsz{width:90px}.col-tstatus{width:140px}.col-estatus{width:100px}
.param-fixed{font-weight:600;color:#303133}.status-cell{display:flex;flex-direction:column;align-items:center;gap:2px}
.record-actions{display:flex;gap:3px;justify-content:center;flex-wrap:wrap}
.pagination-container{display:flex;justify-content:center;margin-top:16px}

.logs-section{margin-bottom:16px}.log-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(480px,1fr));gap:14px}
.log-item{border:1px solid #dcdfe6;border-radius:8px;overflow:hidden}
.log-header{display:flex;justify-content:space-between;align-items:center;padding:8px 14px;background:#f5f7fa;border-bottom:1px solid #dcdfe6}
.log-container{max-height:350px;overflow-y:auto;padding:10px 14px;background:#fff}
.log-content{white-space:pre-wrap;font-family:'Cascadia Code','Fira Code','Consolas',monospace;font-size:12px;color:#303133;margin:0;line-height:1.6}

.filter-card :deep(.el-card__body){padding:14px 18px}
.filter-row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.filter-item{display:flex;align-items:center;gap:6px}.filter-label{font-size:13px;color:#606266;white-space:nowrap}

.save-path-row{display:flex;gap:8px;width:100%}.save-path-row .el-input{flex:1}
.fixed-table{width:100%}

:deep(.el-card){border-radius:12px;border:none}:deep(.el-card__header){padding:12px 18px;border-bottom:1px solid #ebeef5}:deep(.el-card__body){padding:14px 18px}
:deep(.el-table){font-size:13px}:deep(.el-table th.el-table__cell){background-color:#f5f7fa!important;text-align:center!important}:deep(.el-dialog){border-radius:12px}

@media(max-width:1000px){.top-section{flex-direction:column}.dataset-card-top,.config-card-top{flex:auto}.config-row{gap:8px}}
@media(max-width:800px){.main-content{padding:10px;margin-left:180px}.log-grid{grid-template-columns:1fr}.input-row{flex-wrap:wrap}.filter-row{gap:8px}}
</style>
