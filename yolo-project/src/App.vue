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
        <div class="nav-item" :class="{active:activePage==='nodes'}" @click="switchToNodes"><el-icon><Coin /></el-icon><span>节点信息</span></div>
        <div class="nav-item" :class="{active:activePage==='system'}" @click="switchToSystem"><el-icon><Monitor /></el-icon><span>系统资源</span></div>
        <div class="nav-item" :class="{active:activePage==='models'}" @click="switchToModels"><el-icon><Box /></el-icon><span>模型库</span></div>
        <div class="nav-item" :class="{active:activePage==='logs'}" @click="switchToLogs"><el-icon><Document /></el-icon><span>操作日志</span></div>
        <div class="nav-item" v-if="isAdmin" :class="{active:activePage==='users'}" @click="switchToUsers"><el-icon><UserFilled /></el-icon><span>用户管理</span></div>
      </nav>
      <div class="sidebar-footer">
        <div class="user-badge"><el-tag :type="currentUser.role==='ROOT'?'danger':currentUser.role==='ADMIN'?'warning':'info'" size="small">{{ currentUser.role }}</el-tag><span class="user-name">{{ currentUser.username }}</span></div>
        <div class="user-badge"><el-button size="small" @click="showChangePasswordDialog=true"><el-icon><Lock /></el-icon><span class="btn-label">更改密码</span></el-button></div>
        <div class="user-badge"><el-button v-if="currentUser.role==='ROOT'" size="small" type="danger" plain @click="handleCleanup"><el-icon><Delete /></el-icon><span class="btn-label">清空数据集</span></el-button></div>
        <div class="user-badge"><el-button size="small" type="danger" @click="handleLogout"><el-icon><SwitchButton /></el-icon><span class="btn-label">退出系统</span></el-button></div>
      </div>
    </aside>

    <main class="main-content">
      <div v-show="activePage==='main'" class="page-main">
        <div class="page-header"><h2>训练管理</h2></div>
        <div class="top-section">
          <el-card shadow="hover" class="top-card upload-card">
            <template #header><span class="card-title">上传数据集</span></template>
            <div class="upload-area" :class="{'upload-area-active':isDragOver}" @dragover.prevent="isDragOver=true" @dragleave.prevent="isDragOver=false" @drop.prevent="handleDrop">
              <el-icon size="28" class="upload-icon"><UploadFilled /></el-icon>
              <p class="upload-hint">拖拽 ZIP 文件到此处</p>
              <el-upload :show-file-list="false" :before-upload="handleFileUpload" accept=".zip" :disabled="currentStep==='addDataset'">
                <el-button type="primary" size="default" :loading="currentStep==='addDataset'">点击选择 ZIP 文件</el-button>
              </el-upload>
            </div>
          </el-card>
          <el-card shadow="hover" class="top-card config-card">
            <template #header><span class="card-title">全局配置</span></template>
            <div class="config-row">
              <div class="cfg-item"><span class="cfg-label">Epochs</span><el-input-number v-model="defaultEpochs" :min="1" :max="1000" size="small" style="width:100px" @change="handleDefaultEpochsChange" /></div>
              <div class="cfg-item"><span class="cfg-label">Imgsz</span><el-input-number v-model="defaultImgsz" :min="32" :max="1280" :step="32" size="small" style="width:120px" @change="handleDefaultImgszChange" /></div>
            </div>
            <div class="config-row" style="margin-top:8px">
              <div class="cfg-item"><span class="cfg-label">调度模式</span><el-select v-model="schedulingMode" size="small" style="width:100px" @change="handleSchedulingModeChange"><el-option label="自动" value="auto" /><el-option label="手动" value="manual" /></el-select></div>
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
              <table class="records-table"><thead><tr><th class="col-epoch">Epochs</th><th class="col-imgsz">Imgsz</th><th class="col-node">运行节点</th><th class="col-tstatus">训练状态</th><th class="col-estatus">测试状态</th><th class="col-action">操作</th></tr></thead>
                <tbody>
                  <tr v-for="rec in getDatasetRecords(ds.name)" :key="rec.recordName">
                    <td><span class="param-fixed">{{ rec.epochs }}</span></td>
                    <td><span class="param-fixed">{{ rec.imgsz }}</span></td>
                    <td><el-tag v-if="rec.targetNode" size="small" type="primary">{{ rec.targetNode }}</el-tag><span v-else class="param-fixed" style="color:#909399">-</span></td>
                    <td><div class="status-cell"><el-progress v-if="rec.trainStatus==='RUNNING'" :percentage="rec.trainProgress||0" :stroke-width="18" :text-inside="true" :format="p=>p+'%'" style="width:110px" /><el-tag v-else :type="getStatusClass(rec.trainStatus)" size="small">{{ getStatusText(rec.trainStatus,'train') }}</el-tag></div></td>
                    <td><el-tag :type="getStatusClass(rec.testStatus)" size="small">{{ getStatusText(rec.testStatus,'test') }}</el-tag></td>
                    <td><div class="record-actions">
                      <el-button v-if="isEditable(rec)" size="small" type="primary" :disabled="!ds.preprocessed" @click="handleTrainRecord(rec)">训练</el-button>
                      <el-button v-if="hasTrainLog(rec)" size="small" plain @click="handleViewTrainLog(rec)">训练日志</el-button>
                      <el-button v-if="rec.trainStatus==='COMPLETED'" size="small" type="success" @click="showSaveModelDialog(rec)">保存模型</el-button>
                      <el-button v-if="rec.trainStatus==='COMPLETED' && rec.testStatus!=='COMPLETED' && rec.testStatus!=='RUNNING'" size="small" type="success" @click="handleTestRecord(rec)">测试</el-button>
                      <el-button v-if="hasTestLog(rec)" size="small" plain @click="handleViewTestLog(rec)">测试日志</el-button>
                      <el-button v-if="rec.trainStatus==='COMPLETED'" size="small" type="info" plain @click="viewRunsResult(rec.recordName,'train')">训练结果</el-button>
                      <el-button v-if="rec.testStatus==='COMPLETED'" size="small" type="info" plain @click="viewRunsResult(rec.recordName,'test')">测试结果</el-button>
                      <el-button size="small" type="danger" @click="handleDeleteRecord(rec)">删除</el-button>
                    </div></td>
                  </tr>
                  <tr v-for="pending in getPendingRecords(ds.name)" :key="pending.key">
                    <td><el-input-number v-model="pending.epochs" :min="1" :max="1000" size="small" style="width:72px" /></td>
                    <td><el-input-number v-model="pending.imgsz" :min="32" :max="1280" :step="32" size="small" style="width:90px" /></td>
                    <td><el-select v-model="pending.scheduleMode" size="small" style="width:90px" @change="v=>{if(v==='auto')pending.targetNode=''}"><el-option label="自动调度" value="auto" /><el-option label="指定节点" value="manual" /></el-select><el-select v-if="pending.scheduleMode==='manual'" v-model="pending.targetNode" size="small" style="width:90px;margin-left:4px" placeholder="选择节点"><el-option v-for="n in schedulableNodes" :key="n.nodeName" :label="n.nodeName" :value="n.nodeName" /></el-select></td>
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

      <div v-show="activePage==='models'" class="page-models">
        <div class="page-header" style="display:flex;justify-content:space-between;align-items:center"><h2>模型库</h2></div>

        <el-card shadow="hover" style="margin-top:14px">
          <div style="overflow-x:auto">
            <table class="records-table">
              <thead><tr>
                <th>模型名称</th><th>数据集</th><th>类型</th><th>Epochs</th><th>Imgsz</th><th>mAP50</th><th>mAP50-95</th><th>提交者</th><th>创建时间</th><th>操作</th>
              </tr></thead>
              <tbody>
                <tr v-for="m in modelList" :key="m.id">
                  <td><span style="font-weight:600">{{ m.modelName }}</span></td>
                  <td><span>{{ m.dataName }}</span></td>
                  <td><el-tag :type="m.modelType==='best'?'success':'info'" size="small">{{ m.modelType }}</el-tag></td>
                  <td><span>{{ m.epochs }}</span></td>
                  <td><span>{{ m.imgsz }}</span></td>
                  <td><span v-if="m.map50!=null">{{ (m.map50*100).toFixed(1) }}%</span><span v-else>-</span></td>
                  <td><span v-if="m.map5095!=null">{{ (m.map5095*100).toFixed(1) }}%</span><span v-else>-</span></td>
                  <td><span>{{ m.createdBy }}</span></td>
                  <td><span class="param-fixed">{{ m.createdAt }}</span></td>
                  <td><div class="record-actions">
                    <el-button size="small" type="primary" @click="showPredictDialog(m)">推理</el-button>
                    <el-button v-if="m.createdBy===currentUser.username||currentUser.role==='ROOT'" size="small" type="danger" @click="handleDeleteModel(m)">删除</el-button>
                  </div></td>
                </tr>
                <tr v-if="!modelList.length"><td colspan="10" style="color:#909399">暂无模型，训练完成后保存模型到模型库</td></tr>
              </tbody>
            </table>
          </div>
        </el-card>
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
          <div style="overflow-x:auto">
            <table class="records-table">
              <thead><tr>
                <th class="col-luser">用户</th>
                <th class="col-laction">操作</th>
                <th class="col-ltarget">目标</th>
                <th class="col-ldetail">详情</th>
                <th class="col-ltime">时间 ▼</th>
              </tr></thead>
              <tbody>
                <tr v-for="row in pagedLogList" :key="row.id">
                  <td><span>{{ row.username }}</span></td>
                  <td><el-tag :type="getActionTagType(row.action)" size="small">{{ row.action }}</el-tag></td>
                  <td><span>{{ row.target }}</span></td>
                  <td><span>{{ row.detail }}</span></td>
                  <td><span class="param-fixed">{{ row.createdAt }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
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
          <div style="overflow-x:auto">
            <table class="records-table">
              <thead><tr>
                <th class="col-uuser">用户名</th>
                <th class="col-urole">角色</th>
                <th class="col-ucreator">创建者</th>
                <th class="col-utime">创建时间</th>
                <th class="col-uact">操作</th>
              </tr></thead>
              <tbody>
                <tr v-for="row in filteredUserList" :key="row.id">
                  <td><span>{{ row.username }}</span></td>
                  <td><el-tag :type="row.role==='ROOT'?'danger':row.role==='ADMIN'?'warning':'info'" size="small">{{ row.role }}</el-tag></td>
                  <td><span>{{ row.createdBy || '-' }}</span></td>
                  <td><span>{{ row.createdAt }}</span></td>
                  <td><div class="record-actions"><el-button v-if="canDeleteUser(row)" size="small" type="danger" @click="handleDeleteUser(row)">删除</el-button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </div>

      <div v-show="activePage==='nodes'" class="page-nodes">
        <div class="page-header" style="display:flex;justify-content:space-between;align-items:center"><h2>集群节点信息</h2><div v-if="isAdmin"><el-button type="primary" size="small" @click="syncNodes" :loading="nodesSyncing">同步节点</el-button></div></div>

        <div class="cluster-overview">
          <el-card shadow="hover" class="overview-card"><div class="overview-value">{{ clusterOverview.totalNodes || 0 }}</div><div class="overview-label">总节点数</div></el-card>
          <el-card shadow="hover" class="overview-card overview-ready"><div class="overview-value">{{ clusterOverview.readyNodes || 0 }}</div><div class="overview-label">就绪节点</div></el-card>
          <el-card shadow="hover" class="overview-card overview-gpu"><div class="overview-value">{{ clusterOverview.gpuNodes || 0 }}</div><div class="overview-label">GPU节点</div></el-card>
          <el-card shadow="hover" class="overview-card overview-concurrent"><div class="overview-value">{{ totalCpuRemaining }}</div><div class="overview-label">CPU剩余(核)</div></el-card>
          <el-card shadow="hover" class="overview-card overview-remaining"><div class="overview-value">{{ totalMemRemainingGB }}</div><div class="overview-label">内存剩余(GB)</div></el-card>
        </div>

        <el-card shadow="hover" v-for="row in clusterNodes" :key="row.nodeName" style="margin-top:14px">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <div style="display:flex;align-items:center;gap:8px">
                <span style="font-weight:700;font-size:15px">{{ row.nodeName }}</span>
                <el-tag :type="row.roles&&row.roles.includes('control-plane')?'danger':'primary'" size="small">{{ row.roles&&row.roles.includes('control-plane')?'Master':'Worker' }}</el-tag>
                <el-tag :type="row.ready?'success':'danger'" size="small">{{ row.ready?'就绪':'未就绪' }}</el-tag>
                <el-tag v-if="isAdmin" :type="row.schedulable?'success':'warning'" size="small">{{ row.schedulable?'可调度':'已停止' }}</el-tag>
                <span v-if="isAdmin" style="color:#909399;font-size:12px">{{ row.nodeIp }}</span>
              </div>
              <div v-if="isAdmin" class="record-actions">
                <el-button v-if="row.schedulable" size="small" type="warning" @click="cordonNode(row.nodeName)">停止调度</el-button>
                <el-button v-else size="small" type="success" @click="uncordonNode(row.nodeName)">恢复调度</el-button>
              </div>
            </div>
          </template>

          <div class="node-resource-grid">
            <div class="node-res-item">
              <div class="node-res-label">CPU</div>
              <div class="node-res-bar"><el-progress :percentage="row.cpuAllocatable?Math.round(((row.cpuAllocatable?parseFloat(row.cpuAllocatable):0)-(row.cpuRemaining||0))/parseFloat(row.cpuAllocatable)*100):0" :stroke-width="14" :color="row.cpuRemaining<1?'#f56c6c':'#409eff'" /></div>
              <div class="node-res-detail">总量 {{ row.cpuAllocatable }} / 剩余 <span :style="{color:row.cpuRemaining<1?'#f56c6c':'#67c23a',fontWeight:600}">{{ (row.cpuRemaining||0).toFixed(1) }}</span> 核</div>
            </div>
            <div class="node-res-item">
              <div class="node-res-label">内存</div>
              <div class="node-res-bar"><el-progress :percentage="row.memoryAllocatable?Math.round(((formatMemoryMB(row.memoryAllocatable))-(row.memRemainingMB||0))/formatMemoryMB(row.memoryAllocatable)*100):0" :stroke-width="14" :color="row.memRemainingMB<1024?'#f56c6c':'#67c23a'" /></div>
              <div class="node-res-detail">总量 {{ formatMemory(row.memoryAllocatable) }} / 剩余 <span :style="{color:row.memRemainingMB<1024?'#f56c6c':'#67c23a',fontWeight:600}">{{ ((row.memRemainingMB||0)/1024).toFixed(1) }}GB</span></div>
            </div>
            <div class="node-res-item">
              <div class="node-res-label">GPU</div>
              <div class="node-res-bar"><el-progress :percentage="row.gpuAllocatable&&row.gpuAllocatable!=='0'?Math.round(((parseFloat(row.gpuAllocatable))-(row.gpuRemaining||0))/parseFloat(row.gpuAllocatable)*100):0" :stroke-width="14" color="#e6a23c" /></div>
              <div class="node-res-detail">总量 {{ row.gpuAllocatable||'0' }} / 剩余 <span style="font-weight:600">{{ (row.gpuRemaining||0).toFixed(0) }}</span></div>
            </div>
            <div class="node-res-item">
              <div class="node-res-label">加权资源分</div>
              <div class="node-res-score">{{ (row.weightedScore||0).toFixed(1) }}</div>
              <div class="node-res-detail">CPU×10 + 内存(GB)×5 + GPU×50</div>
            </div>
          </div>

          <div v-if="nodeDetailMap[row.nodeName] && nodeDetailMap[row.nodeName].trainingPods && nodeDetailMap[row.nodeName].trainingPods.length" style="margin-top:12px">
            <h4 style="margin:0 0 8px;font-size:13px;color:#606266">运行中的训练任务</h4>
            <div style="overflow-x:auto">
              <table class="records-table" style="font-size:12px">
                <thead><tr><th>Pod名称</th><th>类型</th><th>数据集</th><th>创建者</th><th>CPU请求/上限</th><th>内存请求/上限(MB)</th><th>GPU</th><th>启动时间</th></tr></thead>
                <tbody>
                  <tr v-for="pod in nodeDetailMap[row.nodeName].trainingPods" :key="pod.podName">
                    <td><span style="font-size:11px">{{ pod.podName }}</span></td>
                    <td><el-tag :type="pod.jobType==='train'?'warning':pod.jobType==='test'?'success':'info'" size="small">{{ pod.jobType==='train'?'训练':pod.jobType==='test'?'测试':'预处理' }}</el-tag></td>
                    <td><span>{{ pod.dataName || '-' }}</span></td>
                    <td><span>{{ pod.createdBy || '-' }}</span></td>
                    <td><span>{{ (pod.cpuRequest||0).toFixed(1) }} / {{ (pod.cpuLimit||0).toFixed(1) }}</span></td>
                    <td><span>{{ (pod.memRequestMB||0).toFixed(0) }} / {{ (pod.memLimitMB||0).toFixed(0) }}</span></td>
                    <td><span>{{ pod.gpuRequest||0 }}</span></td>
                    <td><span style="font-size:11px">{{ formatStartTime(pod.startTime) }}</span></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div v-else style="margin-top:8px;color:#909399;font-size:13px;text-align:center;padding:8px">当前无运行中的训练任务</div>
        </el-card>

        <el-card v-if="isAdmin" shadow="hover" style="margin-top:14px">
          <template #header><span class="card-title">节点训练历史</span></template>
          <div style="overflow-x:auto">
            <table class="records-table">
              <thead><tr>
                <th>节点</th><th>记录名</th><th>数据集</th><th>类型</th><th>状态</th><th>Epochs</th><th>Imgsz</th><th>提交者</th><th>开始时间</th><th>结束时间</th>
              </tr></thead>
              <tbody>
                <tr v-for="log in nodeLogs" :key="log.id">
                  <td><el-tag size="small" type="primary">{{ log.nodeName }}</el-tag></td>
                  <td><span>{{ log.recordName }}</span></td>
                  <td><span>{{ log.dataName }}</span></td>
                  <td><el-tag :type="log.taskType==='train'?'warning':'success'" size="small">{{ log.taskType==='train'?'训练':'测试' }}</el-tag></td>
                  <td><el-tag :type="getStatusClass(log.status)" size="small">{{ getStatusText(log.status,log.taskType) }}</el-tag></td>
                  <td><span>{{ log.epochs }}</span></td>
                  <td><span>{{ log.imgsz }}</span></td>
                  <td><span>{{ log.createdBy }}</span></td>
                  <td><span class="param-fixed">{{ log.startedAt }}</span></td>
                  <td><span class="param-fixed">{{ log.finishedAt || '-' }}</span></td>
                </tr>
                <tr v-if="!nodeLogs.length"><td colspan="10" style="color:#909399">暂无训练历史</td></tr>
              </tbody>
            </table>
          </div>
        </el-card>

      </div>

      <div v-show="activePage==='system'" class="page-system">
        <div class="page-header" style="display:flex;justify-content:space-between;align-items:center"><h2>系统资源</h2><el-button type="primary" size="small" @click="loadSystemPods" :loading="systemPodsLoading">刷新</el-button></div>

        <div class="system-summary" v-if="systemPods.length">
          <el-card shadow="hover" class="overview-card"><div class="overview-value">{{ systemPods.length }}</div><div class="overview-label">系统服务总数</div></el-card>
          <el-card shadow="hover" class="overview-card overview-ready"><div class="overview-value">{{ systemPods.filter(p=>p.phase==='Running').length }}</div><div class="overview-label">运行中</div></el-card>
          <el-card shadow="hover" class="overview-card overview-gpu"><div class="overview-value">{{ systemPods.filter(p=>p.phase!=='Running').length }}</div><div class="overview-label">非运行</div></el-card>
          <el-card shadow="hover" class="overview-card overview-concurrent"><div class="overview-value">{{ systemTotalCpu.toFixed(1) }}</div><div class="overview-label">CPU请求(核)</div></el-card>
          <el-card shadow="hover" class="overview-card overview-remaining"><div class="overview-value">{{ (systemTotalMem/1024).toFixed(1) }}</div><div class="overview-label">内存请求(GB)</div></el-card>
        </div>

        <el-card shadow="hover" style="margin-top:14px" v-if="isAdmin">
          <template #header><span class="card-title">系统服务详情</span></template>
          <div style="overflow-x:auto">
            <table class="records-table" style="font-size:12px">
              <thead><tr><th>服务名称</th><th>类型</th><th>命名空间</th><th>状态</th><th>运行节点</th><th>CPU请求/上限</th><th>内存请求/上限(MB)</th><th>重启次数</th><th>启动时间</th><th>运行时长</th></tr></thead>
              <tbody>
                <tr v-for="pod in systemPods" :key="pod.podName">
                  <td><span style="font-size:11px;max-width:200px;display:inline-block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="pod.podName">{{ pod.podName }}</span></td>
                  <td><el-tag :type="getServiceTypeColor(pod.serviceType)" size="small">{{ getServiceTypeLabel(pod.serviceType) }}</el-tag></td>
                  <td><span>{{ pod.namespace }}</span></td>
                  <td><el-tag :type="pod.phase==='Running'?'success':'danger'" size="small">{{ pod.phase }}</el-tag></td>
                  <td><el-tag type="primary" size="small">{{ pod.nodeName }}</el-tag></td>
                  <td><span>{{ (pod.cpuRequest||0).toFixed(2) }} / {{ (pod.cpuLimit||0).toFixed(2) }}</span></td>
                  <td><span>{{ (pod.memRequestMB||0).toFixed(0) }} / {{ (pod.memLimitMB||0).toFixed(0) }}</span></td>
                  <td><span :style="{color:pod.restarts>0?'#f56c6c':'#67c23a'}">{{ pod.restarts }}</span></td>
                  <td><span style="font-size:11px">{{ pod.startTime ? new Date(pod.startTime).toLocaleString() : '-' }}</span></td>
                  <td><span style="font-size:11px">{{ formatStartTime(pod.startTime) }}</span></td>
                </tr>
                <tr v-if="!systemPods.length"><td colspan="10" style="color:#909399">暂无系统服务数据</td></tr>
              </tbody>
            </table>
          </div>
        </el-card>

        <el-card shadow="hover" style="margin-top:14px" v-if="isAdmin && systemPodsByNode.length">
          <template #header><span class="card-title">各节点系统服务资源消耗</span></template>
          <div v-for="nd in systemPodsByNode" :key="nd.nodeName" style="margin-bottom:14px">
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px"><el-tag type="primary">{{ nd.nodeName }}</el-tag><span style="font-size:12px;color:#909399">{{ nd.pods.length }} 个系统服务</span></div>
            <div class="node-resource-grid">
              <div class="node-res-item">
                <div class="node-res-label">系统CPU请求</div>
                <div class="node-res-score" style="font-size:20px">{{ nd.cpuRequest.toFixed(2) }} 核</div>
              </div>
              <div class="node-res-item">
                <div class="node-res-label">系统内存请求</div>
                <div class="node-res-score" style="font-size:20px">{{ (nd.memRequestMB/1024).toFixed(1) }} GB</div>
              </div>
              <div class="node-res-item">
                <div class="node-res-label">服务数</div>
                <div class="node-res-score" style="font-size:20px">{{ nd.pods.length }}</div>
              </div>
              <div class="node-res-item">
                <div class="node-res-label">服务类型</div>
                <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:4px"><el-tag v-for="t in nd.types" :key="t" size="small" :type="getServiceTypeColor(t)">{{ getServiceTypeLabel(t) }}</el-tag></div>
              </div>
            </div>
          </div>
        </el-card>

        <div v-if="!isAdmin" style="text-align:center;padding:40px;color:#909399">系统资源信息仅管理员可查看</div>
      </div>

    <el-dialog title="训练资源预估" v-model="estimateDialogVisible" width="600px" :close-on-click-modal="false">
      <div v-if="estimateLoading" style="text-align:center;padding:20px"><el-icon class="is-loading" size="24"><Loading /></el-icon><p>正在预估资源...</p></div>
      <div v-else-if="estimateResult.error" style="color:#f56c6c">{{ estimateResult.error }}</div>
      <div v-else>
        <div style="margin-bottom:14px">
          <h4 style="margin:0 0 10px;font-size:14px">预计消耗资源</h4>
          <div class="est-res-grid">
            <div class="est-res-item">
              <div class="est-res-label">CPU</div>
              <div class="est-res-bar"><el-progress :percentage="parseFloat(estimateResult.cpuLimit)?Math.round(parseFloat(estimateResult.cpuRequest)/parseFloat(estimateResult.cpuLimit)*100):0" :stroke-width="16" :format="()=>'请求 '+estimateResult.cpuRequest" /></div>
              <div class="est-res-sub">上限 {{ estimateResult.cpuLimit }} 核</div>
            </div>
            <div class="est-res-item">
              <div class="est-res-label">内存</div>
              <div class="est-res-bar"><el-progress :percentage="parseMemToNum(estimateResult.memLimit)?Math.round(parseMemToNum(estimateResult.memRequest)/parseMemToNum(estimateResult.memLimit)*100):0" :stroke-width="16" color="#67c23a" :format="()=>'请求 '+estimateResult.memRequest" /></div>
              <div class="est-res-sub">上限 {{ estimateResult.memLimit }}</div>
            </div>
          </div>
          <div style="margin-top:8px;background:#f0f9eb;padding:8px 12px;border-radius:6px;text-align:center">
            <span style="color:#67c23a;font-size:12px">加权资源消耗</span>
            <div style="font-size:20px;font-weight:700;color:#409eff">{{ estimateWeightedCost }}</div>
            <div style="font-size:11px;color:#909399">CPU×10 + 内存(GB)×5</div>
          </div>
        </div>
        <div v-if="estimateResult.nodeName || estimateResult.autoSelectedNode" style="margin-bottom:12px">
          <h4 style="margin:0 0 10px;font-size:14px">{{ estimateResult.autoSelectedNode ? '自动分配节点' : '指定节点' }}：<el-tag type="primary" size="small">{{ estimateResult.nodeName || estimateResult.autoSelectedNode }}</el-tag></h4>
          <div class="est-res-grid">
            <div class="est-res-item">
              <div class="est-res-label">节点CPU</div>
              <div class="est-res-bar"><el-progress :percentage="parseFloat(estimateResult.nodeCpuTotal)?Math.round(parseFloat(estimateResult.nodeCpuUsed)/parseFloat(estimateResult.nodeCpuTotal)*100):0" :stroke-width="16" :color="parseFloat(estimateResult.nodeCpuRemaining)<1?'#f56c6c':'#409eff'" :format="()=>estimateResult.nodeCpuUsed+' / '+estimateResult.nodeCpuTotal+' 核'" /></div>
              <div class="est-res-sub">剩余 <span :style="{color:parseFloat(estimateResult.nodeCpuRemaining)<1?'#f56c6c':'#67c23a',fontWeight:600}">{{ estimateResult.nodeCpuRemaining }}</span> 核</div>
            </div>
            <div class="est-res-item">
              <div class="est-res-label">节点内存</div>
              <div class="est-res-bar"><el-progress :percentage="parseFloat(estimateResult.nodeMemTotalMB)?Math.round(parseFloat(estimateResult.nodeMemUsedMB)/parseFloat(estimateResult.nodeMemTotalMB)*100):0" :stroke-width="16" :color="parseFloat(estimateResult.nodeMemRemainingMB)<1024?'#f56c6c':'#67c23a'" :format="()=>(parseFloat(estimateResult.nodeMemUsedMB)/1024).toFixed(1)+' / '+(parseFloat(estimateResult.nodeMemTotalMB)/1024).toFixed(1)+' GB'" /></div>
              <div class="est-res-sub">剩余 <span :style="{color:parseFloat(estimateResult.nodeMemRemainingMB)<1024?'#f56c6c':'#67c23a',fontWeight:600}">{{ (parseFloat(estimateResult.nodeMemRemainingMB)/1024).toFixed(1) }}</span> GB</div>
            </div>
          </div>
          <div v-if="estimateResult.cpuAfterTask!=null" style="margin-top:10px">
            <h4 style="margin:0 0 8px;font-size:13px;color:#606266">任务后预计剩余</h4>
            <div class="est-res-grid">
              <div class="est-res-item">
                <div class="est-res-label">CPU</div>
                <div class="est-res-bar"><el-progress :percentage="parseFloat(estimateResult.nodeCpuTotal)?Math.round((parseFloat(estimateResult.nodeCpuTotal)-parseFloat(estimateResult.cpuAfterTask))/parseFloat(estimateResult.nodeCpuTotal)*100):0" :stroke-width="14" :color="parseFloat(estimateResult.cpuAfterTask)<1?'#f56c6c':'#409eff'" :format="()=>estimateResult.cpuAfterTask+' 核'" /></div>
              </div>
              <div class="est-res-item">
                <div class="est-res-label">内存</div>
                <div class="est-res-bar"><el-progress :percentage="parseFloat(estimateResult.nodeMemTotalMB)?Math.round((parseFloat(estimateResult.nodeMemTotalMB)-parseFloat(estimateResult.memAfterTaskMB))/parseFloat(estimateResult.nodeMemTotalMB)*100):0" :stroke-width="14" :color="parseFloat(estimateResult.memAfterTaskMB)<1024?'#f56c6c':'#67c23a'" :format="()=>(parseFloat(estimateResult.memAfterTaskMB)/1024).toFixed(1)+' GB'" /></div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="estimateResult.noAvailableNode" style="color:#f56c6c;text-align:center;padding:10px">当前没有可用节点，任务将进入排队等待</div>
        <el-alert v-if="estimateResult.resourceSufficient===false" title="资源可能不足" type="warning" :closable="false" style="margin-top:8px">当前节点剩余资源可能不足以运行此任务，训练可能会变慢或失败。</el-alert>
      </div>
      <template #footer>
        <el-button @click="estimateDialogVisible=false">取消</el-button>
        <el-button type="primary" @click="confirmEstimateAndTrain">{{ estimateResult.resourceSufficient===false?'仍然继续':'确认训练' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog title="保存模型到模型库" v-model="saveModelDialogVisible" width="480px" :close-on-click-modal="false">
      <el-form :model="saveModelForm" label-width="80px">
        <el-form-item label="记录"><el-tag type="success">{{ saveModelForm.recordName }}</el-tag></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="saveModelForm.modelType"><el-radio label="best">最佳模型(best.pt)</el-radio><el-radio label="last">最后模型(last.pt)</el-radio></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="confirmSaveModel" :loading="saveModelLoading">保存到模型库</el-button></template>
    </el-dialog>

    <el-dialog title="分布式训练配置" v-model="showDistributedTrainDialog" width="520px" :close-on-click-modal="false">
      <el-form :model="distributedTrainForm" label-width="90px">
        <el-form-item label="数据集"><el-tag>{{ distributedTrainForm.dataName }}</el-tag></el-form-item>
        <el-form-item label="Epochs"><el-input-number v-model="distributedTrainForm.epochs" :min="1" :max="1000" /></el-form-item>
        <el-form-item label="Imgsz"><el-input-number v-model="distributedTrainForm.imgsz" :min="32" :max="1280" :step="32" /></el-form-item>
        <el-form-item label="目标节点"><el-select v-model="distributedTrainForm.targetNode" placeholder="自动选择" clearable style="width:100%"><el-option v-for="n in schedulableNodes" :key="n.nodeName" :label="n.nodeName + (n.gpuAllocatable&&n.gpuAllocatable!=='0'?' [GPU:'+n.gpuAllocatable+']':'')" :value="n.nodeName" /></el-select></el-form-item>
        <el-form-item label="GPU资源"><el-select v-model="distributedTrainForm.gpuType" placeholder="无GPU" clearable style="width:100%"><el-option label="NVIDIA GPU" value="nvidia.com/gpu" /></el-select></el-form-item>
        <el-form-item v-if="distributedTrainForm.gpuType" label="GPU数量"><el-input-number v-model="distributedTrainForm.gpuCount" :min="1" :max="8" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showDistributedTrainDialog=false">取消</el-button><el-button type="primary" @click="confirmDistributedTrain">开始训练</el-button></template>
    </el-dialog>

    <el-dialog title="添加用户" v-model="showAddUserDialog" width="360px"><el-form :model="newUserForm" label-width="70px"><el-form-item label="用户名"><el-input v-model="newUserForm.username" /></el-form-item><el-form-item label="密码"><el-input v-model="newUserForm.password" type="password" /></el-form-item><el-form-item v-if="currentUser.role==='ROOT'" label="角色"><el-select v-model="newUserForm.role"><el-option label="普通用户" value="USER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item><el-form-item v-else label="角色"><el-tag type="info">普通用户</el-tag></el-form-item></el-form><template #footer><el-button @click="showAddUserDialog=false">取消</el-button><el-button type="primary" @click="handleAddUser">创建</el-button></template></el-dialog>

    <el-dialog title="确认删除用户" v-model="deleteUserConfirmVisible" width="360px"><p>确定删除用户 <strong>{{ userToDelete.username }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作不可恢复。</p><template #footer><el-button @click="deleteUserConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDeleteUser">确认</el-button></template></el-dialog>

    <el-dialog title="确认删除数据集" v-model="deleteConfirmVisible" width="360px"><p>确定删除数据集 <strong>{{ datasetToDelete }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除所有关联的训练记录、K8s Job/Pod、YAML文件、runs目录和日志文件。</p><template #footer><el-button @click="deleteConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDelete">确认</el-button></template></el-dialog>

    <el-dialog title="确认删除训练记录" v-model="deleteRecordConfirmVisible" width="360px"><p>确定删除训练记录 <strong>{{ recordToDelete }}</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除关联的K8s Job/Pod、YAML文件、runs目录和日志文件。</p><template #footer><el-button @click="deleteRecordConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmDeleteRecord">确认</el-button></template></el-dialog>

    <el-dialog title="确认清空" v-model="cleanupConfirmVisible" width="400px"><p>确定<strong>清空全部数据</strong>？</p><p style="color:#f56c6c;font-size:13px">此操作将删除所有数据集、训练记录、K8s Job/YAML和操作日志，不可恢复。</p><template #footer><el-button @click="cleanupConfirmVisible=false">取消</el-button><el-button type="danger" @click="confirmCleanup" :loading="cleanupLoading">确认</el-button></template></el-dialog>

    <el-dialog title="修改密码" v-model="showChangePasswordDialog" width="360px"><el-form :model="changePasswordForm" label-width="70px"><el-form-item label="旧密码"><el-input v-model="changePasswordForm.oldPassword" type="password" placeholder="请输入旧密码" /></el-form-item><el-form-item label="新密码"><el-input v-model="changePasswordForm.newPassword" type="password" placeholder="请输入新密码（至少4位）" /></el-form-item><el-form-item label="确认"><el-input v-model="changePasswordForm.confirmPassword" type="password" placeholder="再次输入新密码" /></el-form-item></el-form><template #footer><el-button @click="showChangePasswordDialog=false">取消</el-button><el-button type="primary" @click="handleChangePassword">确认修改</el-button></template></el-dialog>

    <el-dialog :title="runsResultTitle" v-model="runsResultVisible" width="700px" :close-on-click-modal="false">
      <div v-if="runsResultLoading" style="text-align:center;padding:30px"><el-icon class="is-loading" size="24"><Loading /></el-icon><p>加载中...</p></div>
      <div v-else-if="!runsResultData.exists" style="text-align:center;padding:30px;color:#909399">结果目录不存在</div>
      <div v-else>
        <div v-if="runsResultData.resultsCsv" style="margin-bottom:14px">
          <h4 style="margin:0 0 8px;font-size:14px;color:#303133">results.csv</h4>
          <div style="max-height:250px;overflow:auto;border:1px solid #ebeef5;border-radius:6px;padding:10px;background:#fafafa">
            <pre style="margin:0;font-size:12px;white-space:pre-wrap;font-family:'Cascadia Code','Fira Code','Consolas',monospace">{{ runsResultData.resultsCsv }}</pre>
          </div>
        </div>
        <h4 style="margin:0 0 8px;font-size:14px;color:#303133">文件列表</h4>
        <div style="max-height:300px;overflow:auto">
          <table class="records-table" style="font-size:12px">
            <thead><tr><th>文件名</th><th>路径</th><th>类型</th><th>大小</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="f in runsResultData.files" :key="f.path">
                <td><span>{{ f.name }}</span></td>
                <td><span style="color:#909399;font-size:11px">{{ f.path }}</span></td>
                <td><el-tag v-if="f.isDirectory" size="small" type="info">目录</el-tag><el-tag v-else-if="f.isImage" size="small" type="success">图片</el-tag><el-tag v-else-if="f.isCsv" size="small" type="warning">CSV</el-tag><el-tag v-else-if="f.isText" size="small">文本</el-tag><el-tag v-else size="small" type="info">文件</el-tag></td>
                <td><span v-if="!f.isDirectory">{{ (f.size/1024).toFixed(1) }}KB</span></td>
                <td><el-button v-if="f.isText && !f.isDirectory" size="small" @click="loadRunsFile(runsResultCurrentRecord,runsResultCurrentType,f.path)">查看</el-button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer><el-button @click="runsResultVisible=false">关闭</el-button></template>
    </el-dialog>

    <el-dialog :title="runsFileTitle" v-model="runsFileVisible" width="600px">
      <div style="max-height:400px;overflow:auto;border:1px solid #ebeef5;border-radius:6px;padding:10px;background:#fafafa">
        <pre style="margin:0;font-size:12px;white-space:pre-wrap;font-family:'Cascadia Code','Fira Code','Consolas',monospace">{{ runsFileContent }}</pre>
      </div>
      <template #footer><el-button @click="runsFileVisible=false">关闭</el-button></template>
    </el-dialog>

    <el-dialog title="模型推理 - 使用训练好的模型对数据集进行目标检测" v-model="predictDialogVisible" width="560px">
      <el-form label-width="90px">
        <el-form-item label="模型"><el-tag type="success">{{ predictForm.modelName }}</el-tag></el-form-item>
        <el-form-item label="目标数据集"><el-select v-model="predictForm.dataName" placeholder="选择要进行推理检测的数据集" style="width:100%"><el-option v-for="ds in datasetList" :key="ds.name" :label="ds.name + (ds.preprocessed?' (已预处理)':' (未预处理)')" :value="ds.name" /></el-select></el-form-item>
        <el-form-item><div style="color:#909399;font-size:12px;line-height:1.6">将使用所选模型对目标数据集中的图片进行推理检测，检测结果将保存到runs目录中。推理完成后可查看标注后的图片结果。</div></el-form-item>
      </el-form>
      <template #footer><el-button @click="predictDialogVisible=false">取消</el-button><el-button type="primary" @click="handlePredict" :loading="predictLoading">开始推理</el-button></template>
    </el-dialog>

    <el-dialog title="推理结果" v-model="predictResultVisible" width="800px">
      <div v-if="predictResultLoading" style="text-align:center;padding:30px"><el-icon class="is-loading" size="24"><Loading /></el-icon><p>推理中，请稍候...</p></div>
      <div v-else-if="predictResultImages.length===0" style="text-align:center;padding:30px;color:#909399">暂无推理结果图片</div>
      <div v-else style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:10px;max-height:500px;overflow:auto">
        <div v-for="img in predictResultImages" :key="img.name" style="border:1px solid #ebeef5;border-radius:6px;overflow:hidden">
          <img :src="getPredictImageUrl(img.name)" style="width:100%;display:block;cursor:pointer" @click="openPredictImage(img.name)" />
          <div style="padding:4px 8px;font-size:11px;color:#606266;text-overflow:ellipsis;overflow:hidden;white-space:nowrap">{{ img.name }}</div>
        </div>
      </div>
      <template #footer><el-button @click="predictResultVisible=false">关闭</el-button></template>
    </el-dialog>
  </main>
</div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, computed, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Monitor, Document, UserFilled, Coin, Lock, Delete, SwitchButton, Loading, Box } from '@element-plus/icons-vue'
import axios from 'axios'

const isLoggedIn=ref(false)
const loginForm=reactive({username:'',password:''})
const loginLoading=ref(false),loginError=ref('')
const currentUser=reactive({username:'',role:''})
const token=ref('')
const defaultEpochs=ref(2),savedDefaultEpochs=ref(2),defaultImgsz=ref(640),savedDefaultImgsz=ref(640)
const currentStep=ref(''),datasetList=ref([]),deleteConfirmVisible=ref(false),cleanupConfirmVisible=ref(false),cleanupLoading=ref(false),datasetToDelete=ref('')
const deleteRecordConfirmVisible=ref(false),recordToDelete=ref('')
const processingStatus=ref({}),testLoadingStatus=ref({}),trainingRecords=ref([]),userList=ref([]),operationLogs=ref([]),showAddUserDialog=ref(false)
const newUserForm=reactive({username:'',password:'',role:'USER'})
const pendingRecords=ref({}),preprocessLogs=ref({}),trainLogs=ref({}),testLogs=ref({})
const logRefs=ref({})
const saveModelDialogVisible=ref(false),saveModelForm=reactive({dataName:'',modelType:'best',savePath:'',recordName:''}),saveModelLoading=ref(false)
const deleteUserConfirmVisible=ref(false),userToDelete=ref({})
const showChangePasswordDialog=ref(false),changePasswordForm=reactive({oldPassword:'',newPassword:'',confirmPassword:''})
const currentPage=ref(1),pageSize=ref(10),total=ref(0)
const isDragOver=ref(false)
const activePage=ref('main')
const preprocessTimers=ref({})

const logFilter=reactive({username:'',action:'',startTime:'',endTime:''})
const logActions=['ADD_DATASET','UPLOAD_DATASET','PREPROCESS','TRAIN','TEST','SAVE_MODEL','DELETE_DATASET','DELETE_RECORD','CONFIG_CHANGE','CREATE_USER','UPDATE_ROLE','DELETE_USER','CHANGE_PASSWORD']
const logPage=ref(1),logPageSize=ref(20)
const userSearch=ref(''),userRoleFilter=ref('')

const clusterNodes=ref([]),clusterOverview=reactive({totalNodes:0,readyNodes:0,gpuNodes:0,masterNodes:0,workerNodes:0}),nodesSyncing=ref(false),schedulingMode=ref('auto')
const nodeLogs=ref([])
const nodeDetailMap=ref({})
const systemPods=ref([]),systemPodsLoading=ref(false)
const runsResultVisible=ref(false),runsResultLoading=ref(false),runsResultData=ref({}),runsResultTitle=ref(''),runsResultCurrentRecord=ref(''),runsResultCurrentType=ref('')
const runsFileVisible=ref(false),runsFileTitle=ref(''),runsFileContent=ref('')
const modelList=ref([])
const predictDialogVisible=ref(false),predictForm=reactive({modelId:null,modelName:'',dataName:''}),predictLoading=ref(false)
const predictResultVisible=ref(false),predictResultLoading=ref(false),predictResultImages=ref([]),predictResultModelId=ref(null),predictResultDataName=ref('')
const showDistributedTrainDialog=ref(false),distributedTrainForm=reactive({dataName:'',epochs:2,imgsz:640,targetNode:'',gpuType:'',gpuCount:1})
const schedulableNodes=computed(()=>clusterNodes.value.filter(n=>n.ready&&n.schedulable))
const totalMaxConcurrent=computed(()=>clusterNodes.value.filter(n=>n.ready&&n.schedulable).reduce((sum,n)=>sum+(n.maxConcurrentTasks||1),0))
const totalRemainingSlots=computed(()=>clusterNodes.value.filter(n=>n.ready&&n.schedulable).reduce((sum,n)=>sum+(n.remainingSlots||0),0))
const totalCpuRemaining=computed(()=>clusterNodes.value.filter(n=>n.ready&&n.schedulable).reduce((sum,n)=>sum+(n.cpuRemaining||0),0).toFixed(1))
const totalMemRemainingGB=computed(()=>clusterNodes.value.filter(n=>n.ready&&n.schedulable).reduce((sum,n)=>sum+((n.memRemainingMB||0)/1024),0).toFixed(1))
const systemTotalCpu=computed(()=>systemPods.value.reduce((sum,p)=>sum+(p.cpuRequest||0),0))
const systemTotalMem=computed(()=>systemPods.value.reduce((sum,p)=>sum+(p.memRequestMB||0),0))
const systemPodsByNode=computed(()=>{const map={};systemPods.value.forEach(p=>{const n=p.nodeName||'-';if(!map[n])map[n]={nodeName:n,pods:[],cpuRequest:0,memRequestMB:0,types:new Set()};map[n].pods.push(p);map[n].cpuRequest+=p.cpuRequest||0;map[n].memRequestMB+=p.memRequestMB||0;if(p.serviceType)map[n].types.add(p.serviceType)});return Object.values(map).map(nd=>({...nd,types:[...nd.types]}))})
const workerNodesOnly=computed(()=>clusterNodes.value.filter(n=>!n.roles?.includes('control-plane')&&!n.roles?.includes('master')))

const wsConnections=new Map()
let statusRefreshTimer=null

const isAdmin=computed(()=>currentUser.role==='ROOT'||currentUser.role==='ADMIN')
const pagedDatasets=computed(()=>{const s=(currentPage.value-1)*pageSize.value;return datasetList.value.slice(s,s+pageSize.value)})
const api=computed(()=>axios.create({headers:{Authorization:`Bearer ${token.value}`}}))
const showMsg=(msg,type='success')=>ElMessage({message:msg,type,duration:3000,center:true})

const filteredLogs=computed(()=>{let list=[...operationLogs.value];if(logFilter.username){const kw=logFilter.username.toLowerCase();list=list.filter(l=>l.username&&l.username.toLowerCase().includes(kw))}if(logFilter.action){list=list.filter(l=>l.action===logFilter.action)}if(logFilter.startTime){try{const s=new Date(logFilter.startTime);list=list.filter(l=>l.createdAt&&new Date(l.createdAt)>=s)}catch(e){}}if(logFilter.endTime){try{const e=new Date(logFilter.endTime);list=list.filter(l=>l.createdAt&&new Date(l.createdAt)<=e)}catch(e){}}return list})
const pagedLogList=computed(()=>{const sorted=[...filteredLogs.value].sort((a,b)=>new Date(b.createdAt).getTime()-new Date(a.createdAt).getTime());const s=(logPage.value-1)*logPageSize.value;return sorted.slice(s,s+logPageSize.value)})
const filteredUserList=computed(()=>{let list=[...userList.value];if(userSearch.value){const kw=userSearch.value.toLowerCase();list=list.filter(u=>u.username.toLowerCase().includes(kw))}if(userRoleFilter.value){list=list.filter(u=>u.role===userRoleFilter.value)}return list})

const handleFileUpload=async(file)=>{const dataName=file.name.replace(/\.zip$/i,'');currentStep.value='addDataset';try{const formData=new FormData();formData.append('file',file);formData.append('dataName',dataName);const r=await api.value.post('/api/datasets/upload',formData,{headers:{'Content-Type':'multipart/form-data'},timeout:0,maxContentLength:Infinity,maxBodyLength:Infinity});showMsg(`数据集 ${dataName} 上传成功`);await refreshDatasets();await loadTrainingRecords()}catch(e){showMsg(`上传失败: ${e.response?.data?.message||e.message}`,'error')}finally{currentStep.value='';return false}}
const canDeleteUser=(row)=>{if(!row||row.role==='ROOT')return false;if(currentUser.username===row.username)return false;if(currentUser.role==='ROOT'&&row.role!=='ROOT')return true;if(currentUser.role==='ADMIN'&&row.role==='USER'&&currentUser.username===row.createdBy)return true;return false}
const getDatasetRecords=(dataName)=>trainingRecords.value.filter(r=>r.dataName===dataName)
const getPendingRecords=(dataName)=>pendingRecords.value[dataName]||[]
const isEditable=(rec)=>rec.trainStatus==='IDLE'&&(!rec.trainJobId||rec.trainJobId==='')
const hasTrainLog=(rec)=>rec.trainStatus!=='IDLE'||rec.trainJobId
const hasTestLog=(rec)=>rec.testStatus&&rec.testStatus!=='IDLE'
const addPendingRecord=(dataName)=>{if(!pendingRecords.value[dataName])pendingRecords.value[dataName]=[];const k='pending-'+Date.now();pendingRecords.value[dataName].push({key:k,epochs:savedDefaultEpochs.value,imgsz:savedDefaultImgsz.value,scheduleMode:'auto',targetNode:''})}
const removePendingRecord=(dataName,key)=>{if(pendingRecords.value[dataName])pendingRecords.value[dataName]=pendingRecords.value[dataName].filter(r=>r.key!==key)}

const switchToLogs=()=>{activePage.value='logs';loadOperationLogs()}
const switchToUsers=()=>{activePage.value='users';loadUsers()}
const switchToNodes=()=>{activePage.value='nodes';loadClusterNodes();loadClusterOverview();loadNodeLogs()}
const switchToSystem=()=>{activePage.value='system';loadSystemPods()}
const loadSystemPods=async()=>{systemPodsLoading.value=true;try{const r=await api.value.get('/api/nodes/system-pods');systemPods.value=r.data}catch(e){systemPods.value=[]}finally{systemPodsLoading.value=false}}
const loadNodeLogs=async()=>{if(!isAdmin.value)return;try{const r=await api.value.get('/api/nodes/logs');nodeLogs.value=r.data}catch(e){}}

const loadClusterNodes=async()=>{try{const r=await api.value.get('/api/nodes');clusterNodes.value=r.data.map(n=>({...n,currentTasks:n.currentTasks||0,remainingSlots:n.remainingSlots!=null?n.remainingSlots:(n.maxConcurrentTasks||1)-(n.currentTasks||0)}));if(isAdmin.value){const detailPromises=clusterNodes.value.map(async n=>{try{const d=await api.value.get(`/api/nodes/${n.nodeName}`);nodeDetailMap.value[n.nodeName]=d.data}catch(e){}});await Promise.all(detailPromises)}}catch(e){}}
const loadClusterOverview=async()=>{try{const r=await api.value.get('/api/nodes/overview');Object.assign(clusterOverview,r.data)}catch(e){}}
const syncNodes=async()=>{nodesSyncing.value=true;try{await api.value.post('/api/nodes/sync');await loadClusterNodes();await loadClusterOverview();showMsg('节点同步完成')}catch(e){showMsg('同步失败','error')}finally{nodesSyncing.value=false}}
const cordonNode=async(name)=>{try{await api.value.post(`/api/nodes/${name}/cordon`);showMsg(`节点 ${name} 已停止调度`);await loadClusterNodes()}catch(e){showMsg('操作失败','error')}}
const uncordonNode=async(name)=>{try{await api.value.post(`/api/nodes/${name}/uncordon`);showMsg(`节点 ${name} 已恢复调度`);await loadClusterNodes()}catch(e){showMsg('操作失败','error')}}
const handleSchedulingModeChange=async(v)=>{try{await api.value.post('/api/scheduler/scheduling-mode',{mode:v});showMsg(`调度模式已设为 ${v==='auto'?'自动':'手动'}`)}catch(e){showMsg('更新失败','error')}}

const showDistributedTrain=(dataName)=>{distributedTrainForm.dataName=dataName;distributedTrainForm.epochs=savedDefaultEpochs.value;distributedTrainForm.imgsz=savedDefaultImgsz.value;distributedTrainForm.targetNode='';distributedTrainForm.gpuType='';distributedTrainForm.gpuCount=1;showDistributedTrainDialog.value=true}
const confirmDistributedTrain=async()=>{const req={dataName:distributedTrainForm.dataName,epochs:distributedTrainForm.epochs,imgsz:distributedTrainForm.imgsz};if(distributedTrainForm.targetNode)req.targetNode=distributedTrainForm.targetNode;if(distributedTrainForm.gpuType&&distributedTrainForm.gpuCount>0){req.gpuResources={};req.gpuResources[distributedTrainForm.gpuType]=String(distributedTrainForm.gpuCount)}try{const r=await api.value.post('/api/scheduler/add',req);showMsg(r.data.message);showDistributedTrainDialog.value=false;await loadTrainingRecords();const rn=`${distributedTrainForm.dataName}-e${distributedTrainForm.epochs}-i${distributedTrainForm.imgsz}`;trainLogs.value[rn]='';connectLogWebSocket(rn,'train');startStatusRefresh()}catch(e){showMsg(e.response?.data?.message||'提交失败','error')}}

const handleLogin=async()=>{if(loginLoading.value)return;loginLoading.value=true;loginError.value='';try{const r=await axios.post('/api/auth/login',loginForm);token.value=r.data.token;currentUser.username=r.data.username;currentUser.role=r.data.role;isLoggedIn.value=true;localStorage.setItem('token',token.value);localStorage.setItem('username',currentUser.username);localStorage.setItem('role',currentUser.role);onLoginSuccess()}catch(e){loginError.value=e.response?.data?.message||'登录失败'}finally{loginLoading.value=false}}

const handleLogout=()=>{token.value='';currentUser.username='';currentUser.role='';isLoggedIn.value=false;localStorage.removeItem('token');localStorage.removeItem('username');localStorage.removeItem('role');stopAllWs();stopStatusRefresh();stopAllPreprocessTimers();datasetList.value=[];trainingRecords.value=[];userList.value=[];operationLogs.value=[];Object.keys(pendingRecords.value).forEach(k=>delete pendingRecords.value[k]);Object.keys(preprocessLogs.value).forEach(k=>delete preprocessLogs.value[k]);Object.keys(trainLogs.value).forEach(k=>delete trainLogs.value[k]);Object.keys(testLogs.value).forEach(k=>delete testLogs.value[k]);Object.keys(processingStatus.value).forEach(k=>delete processingStatus.value[k]);Object.keys(testLoadingStatus.value).forEach(k=>delete testLoadingStatus.value[k]);currentPage.value=1;total.value=0;activePage.value='main'}

const onLoginSuccess=async()=>{try{await Promise.all([refreshDatasets(),loadTrainingRecords(),loadOperationLogs(),loadClusterNodes(),loadModelList()]);if(isAdmin.value)await loadUsers();const r=await api.value.get('/api/scheduler/config');if(r.data){defaultEpochs.value=r.data.defaultEpochs;savedDefaultEpochs.value=r.data.defaultEpochs;defaultImgsz.value=r.data.defaultImgsz;savedDefaultImgsz.value=r.data.defaultImgsz;if(r.data.schedulingMode)schedulingMode.value=r.data.schedulingMode}startStatusRefresh()}catch(e){}}

const loadTrainingRecords=async()=>{try{const oldRecords=[...trainingRecords.value];trainingRecords.value=(await api.value.get('/api/training-records')).data;checkStatusChanges(oldRecords,trainingRecords.value)}catch(e){}}
const loadUsers=async()=>{try{const params={};if(userSearch.value)params.search=userSearch.value;if(userRoleFilter.value)params.role=userRoleFilter.value;userList.value=(await api.value.get('/api/users',{params})).data}catch(e){}}
const loadOperationLogs=async()=>{try{const params={};if(logFilter.username)params.username=logFilter.username;if(logFilter.action)params.action=logFilter.action;if(logFilter.startTime)params.startTime=logFilter.startTime;if(logFilter.endTime)params.endTime=logFilter.endTime;operationLogs.value=(await api.value.get('/api/users/logs',{params})).data}catch(e){}}
const refreshDatasets=async()=>{try{const r=await api.value.get('/api/datasets');datasetList.value=r.data;total.value=r.data.length}catch(e){if(e.response?.status===401)handleLogout()}}

const fetchFilteredLogs=async()=>{logPage.value=1;await loadOperationLogs()}
const resetLogFilter=()=>{logFilter.username='';logFilter.action='';logFilter.startTime='';logFilter.endTime='';logPage.value=1;loadOperationLogs()}
const fetchFilteredUsers=async()=>{await loadUsers()}

const getActionTagType=(action)=>{const map={ADD_DATASET:'success',UPLOAD_DATASET:'success',PREPROCESS:'warning',TRAIN:'primary',TEST:'primary',SAVE_MODEL:'success',DELETE_DATASET:'danger',DELETE_RECORD:'danger',CONFIG_CHANGE:'info',CREATE_USER:'success',UPDATE_ROLE:'warning',DELETE_USER:'danger',CHANGE_PASSWORD:'warning'};return map[action]||'info'}

const handleDrop=async(e)=>{isDragOver.value=false;const files=e.dataTransfer.files;if(!files||files.length===0)return;for(let i=0;i<files.length;i++){const f=files[i];if(f.name.endsWith('.zip')){await handleFileUpload(f)}else{showMsg('请上传 ZIP 格式文件','warning')}}}

const handlePreprocessDataset=async(dataName)=>{processingStatus.value[dataName]=true;preprocessLogs.value[dataName]=`正在预处理 ${dataName}...\n`;try{const r=await api.value.post('/api/preprocess',{dataName});showMsg('预处理任务已启动');pollPreprocess(r.data.jobId,dataName)}catch(e){preprocessLogs.value[dataName]+=`错误: ${e.message}\n`;showMsg('预处理失败','error');processingStatus.value[dataName]=false}}

const pollPreprocess=(jobId,dataName)=>{const tid=`pre-${dataName}-${Date.now()}`;const iv=setInterval(async()=>{try{const r=await api.value.get(`/api/status/${jobId}`);if(r.data){if(r.data.log)preprocessLogs.value[dataName]=r.data.log;if(r.data.status==='COMPLETED'||r.data.status==='DONE'||r.data.status==='FAILED'){clearInterval(iv);delete preprocessTimers.value[tid];processingStatus.value[dataName]=false;if(r.data.status==='COMPLETED'||r.data.status==='DONE')showMsg(`数据集 ${dataName} 预处理成功`);else showMsg(`预处理失败`,'error');refreshDatasets()}}}catch(e){clearInterval(iv);delete preprocessTimers.value[tid];processingStatus.value[dataName]=false}},1000);preprocessTimers.value[tid]=iv}

const stopAllPreprocessTimers=()=>{Object.values(preprocessTimers.value).forEach(id=>clearInterval(id));preprocessTimers.value={}}

const estimateResult=ref({})
const estimateDialogVisible=ref(false)
const estimateLoading=ref(false)
const estimateCallback=ref(null)
const estimateWeightedCost=computed(()=>{const r=estimateResult.value;if(!r.cpuRequest)return '0';const cpuCores=parseFloat(r.cpuRequest)||0;const memGB=parseMemToNum(r.memRequest)/1024;return (cpuCores*10+memGB*5).toFixed(1)})
const parseMemToNum=v=>{if(!v)return 0;const s=String(v).trim();const m=s.match(/^(\d+(?:\.\d+)?)(Ki|Mi|Gi|Ti|K|M|G)?$/i);if(m){const num=parseFloat(m[1]);const unit=(m[2]||'').toUpperCase();if(unit==='KI')return num/1024;if(unit==='MI')return num;if(unit==='GI')return num*1024;if(unit==='K')return num/1000;if(unit==='M')return num;if(unit==='G')return num*1000;return num}return parseFloat(s)||0}

const showEstimateDialog=async(dataName,epochs,imgsz,targetNode,callback)=>{estimateLoading.value=true;estimateDialogVisible.value=true;estimateCallback.value=callback;try{const req={dataName,epochs,imgsz};if(targetNode)req.targetNode=targetNode;const r=await api.value.post('/api/scheduler/estimate',req);estimateResult.value=r.data}catch(e){estimateResult.value={error:e.response?.data?.message||'预估失败'}}finally{estimateLoading.value=false}}

const confirmEstimateAndTrain=()=>{estimateDialogVisible.value=false;if(estimateCallback.value)estimateCallback.value()}

const handleTrainRecord=async(rec)=>{const rn=rec.recordName;const ep=rec.epochs||defaultEpochs.value;const im=rec.imgsz||defaultImgsz.value;showEstimateDialog(rec.dataName,ep,im,null,async()=>{trainLogs.value[rn]='';try{const r=await api.value.post('/api/scheduler/add',{dataName:rec.dataName,epochs:ep,imgsz:im});showMsg(r.data.message);await loadTrainingRecords();connectLogWebSocket(rn,'train');startStatusRefresh()}catch(e){delete trainLogs.value[rn];showMsg(e.response?.data?.message||'加入队列失败','error')}})}

const handleTrainPending=async(dataName,pending)=>{const rn=`${dataName}-e${pending.epochs}-i${pending.imgsz}`;const targetNode=pending.scheduleMode==='manual'?pending.targetNode:null;showEstimateDialog(dataName,pending.epochs,pending.imgsz,targetNode,async()=>{trainLogs.value[rn]='';try{const req={dataName,epochs:pending.epochs,imgsz:pending.imgsz};if(pending.scheduleMode==='manual'&&pending.targetNode)req.targetNode=pending.targetNode;const r=await api.value.post('/api/scheduler/add',req);showMsg(r.data.message);removePendingRecord(dataName,pending.key);await loadTrainingRecords();connectLogWebSocket(rn,'train');startStatusRefresh()}catch(e){delete trainLogs.value[rn];showMsg(e.response?.data?.message||'加入队列失败','error')}})}

const handleTestRecord=async(rec)=>{testLogs.value[rec.recordName]='';testLoadingStatus.value[rec.recordName]=true;try{const r=await api.value.post('/api/test',{dataName:rec.dataName,imgsz:rec.imgsz,recordName:rec.recordName});showMsg('测试任务已启动');await loadTrainingRecords();connectLogWebSocket(rec.recordName,'test');startStatusRefresh()}catch(e){testLogs.value[rec.recordName]=`错误: ${e.message}\n`;testLoadingStatus.value[rec.recordName]=false}}

const connectLogWebSocket=(recordName,type)=>{const logKey=recordName+'-'+type;if(wsConnections.has(logKey)){const old=wsConnections.get(logKey);if(old.readyState===WebSocket.OPEN||old.readyState===WebSocket.CONNECTING)return}const wsUrl=`ws://${window.location.host}/ws/logs/${recordName}/${type}`;const ws=new WebSocket(wsUrl);ws.onopen=()=>{};ws.onmessage=(event)=>{if(type==='train'){trainLogs.value[recordName]=(trainLogs.value[recordName]||'')+event.data}else if(type==='test'){testLogs.value[recordName]=(testLogs.value[recordName]||'')+event.data}autoScroll(recordName,type)};ws.onclose=()=>{wsConnections.delete(logKey);refreshDatasets();loadTrainingRecords()};ws.onerror=()=>{wsConnections.delete(logKey)};wsConnections.set(logKey,ws)}

const handleViewTrainLog=async(rec)=>{const rn=rec.recordName;try{if(rec.trainStatus==='RUNNING'||rec.trainStatus==='QUEUED'){connectLogWebSocket(rn,'train')}else{const r=await api.value.get(`/api/training-records/${rn}/train-log`);if(r.data.log)trainLogs.value[rn]=r.data.log;scrollToBottomNow(rn,'train')}}catch(e){trainLogs.value[rn]=`获取日志失败: ${e.message}`}}

const handleViewTestLog=async(rec)=>{const rn=rec.recordName;try{if(rec.testStatus==='RUNNING'||rec.testStatus==='QUEUED'){connectLogWebSocket(rn,'test')}else{const r=await api.value.get(`/api/training-records/${rn}/test-log`);if(r.data.log)testLogs.value[rn]=r.data.log;scrollToBottomNow(rn,'test')}}catch(e){testLogs.value[rn]=`获取日志失败: ${e.message}`}}

const closeTrainLog=n=>{delete trainLogs.value[n];closeWsConnection(n+'-train')}
const closeTestLog=n=>{delete testLogs.value[n];closeWsConnection(n+'-test')}
const closePreprocessLog=n=>{delete preprocessLogs.value[n]}

const closeWsConnection=key=>{try{const ws=wsConnections.get(key);if(ws){try{if(ws.readyState===WebSocket.OPEN||ws.readyState===WebSocket.CONNECTING)ws.close()}catch(e){}wsConnections.delete(key)}}catch(e){}}
const stopAllWs=()=>{try{wsConnections.forEach(ws=>{try{if(ws.readyState===WebSocket.OPEN||ws.readyState===WebSocket.CONNECTING)ws.close()}catch(e){}});wsConnections.clear()}catch(e){}}

const checkStatusChanges=(oldRecords,newRecords)=>{for(const rec of newRecords){const old=oldRecords.find(r=>r.recordName===rec.recordName);const trainLogOpen=rec.recordName in trainLogs.value;const testLogOpen=rec.recordName in testLogs.value;if(trainLogOpen){if(old&&old.trainStatus!=='RUNNING'&&rec.trainStatus==='RUNNING'){connectLogWebSocket(rec.recordName,'train')}if(old&&old.trainStatus==='RUNNING'&&rec.trainStatus==='COMPLETED'){closeWsConnection(rec.recordName+'-train');showMsg(`${rec.recordName} 训练完成`)}if(old&&old.trainStatus==='RUNNING'&&rec.trainStatus==='FAILED'){closeWsConnection(rec.recordName+'-train');showMsg(`${rec.recordName} 训练失败`,'error')}}if(testLogOpen){if(old&&old.testStatus!=='RUNNING'&&rec.testStatus==='RUNNING'){connectLogWebSocket(rec.recordName,'test')}if(old&&old.testStatus==='RUNNING'&&rec.testStatus==='COMPLETED'){closeWsConnection(rec.recordName+'-test');showMsg(`${rec.recordName} 测试完成`)}if(old&&old.testStatus==='RUNNING'&&rec.testStatus==='FAILED'){closeWsConnection(rec.recordName+'-test');showMsg(`${rec.recordName} 测试失败`,'error')}}}}

const hasActiveTasks=()=>trainingRecords.value.some(r=>r.trainStatus==='RUNNING'||r.trainStatus==='QUEUED'||r.testStatus==='RUNNING'||r.testStatus==='QUEUED')

const startStatusRefresh=()=>{if(statusRefreshTimer)return;statusRefreshTimer=setInterval(async()=>{if(!hasActiveTasks()&&Object.keys(preprocessLogs.value).length===0){stopStatusRefresh();return}await loadTrainingRecords()},3000)}
const stopStatusRefresh=()=>{if(statusRefreshTimer){clearInterval(statusRefreshTimer);statusRefreshTimer=null}}

const handleDeleteRecord=(rec)=>{recordToDelete.value=rec.recordName;deleteRecordConfirmVisible.value=true}
const confirmDeleteRecord=async()=>{const rn=recordToDelete.value;try{closeWsConnection(rn+'-train');closeWsConnection(rn+'-test');delete trainLogs.value[rn];delete testLogs.value[rn];await api.value.delete(`/api/training-records/${encodeURIComponent(rn)}`);showMsg('训练记录已删除');await refreshDatasets();await loadTrainingRecords()}catch(e){showMsg(e.response?.data?.message||(e.response?.status===404?'记录不存在':'删除失败'),'error')}finally{deleteRecordConfirmVisible.value=false}}

const showSaveModelDialog=rec=>{saveModelForm.dataName=rec.dataName;saveModelForm.recordName=rec.recordName;saveModelForm.modelType='best';saveModelForm.savePath='';saveModelDialogVisible.value=true}
const confirmSaveModel=async()=>{saveModelLoading.value=true;try{const r=await api.value.post('/api/models',{recordName:saveModelForm.recordName,modelType:saveModelForm.modelType});if(r.data.status==='success'){showMsg('模型已保存到模型库');saveModelDialogVisible.value=false;loadModelList()}else{showMsg(r.data.message||'保存失败','error')}}catch(e){showMsg(e.response?.data?.message||'保存失败','error')}finally{saveModelLoading.value=false}}

const handleDeleteDataset=dn=>{datasetToDelete.value=dn;deleteConfirmVisible.value=true}
const confirmDelete=async()=>{try{const dn=datasetToDelete.value;const recs=trainingRecords.value.filter(r=>r.dataName===dn);recs.forEach(r=>{closeWsConnection(r.recordName+'-train');closeWsConnection(r.recordName+'-test');delete trainLogs.value[r.recordName];delete testLogs.value[r.recordName]});delete preprocessLogs.value[dn];delete processingStatus.value[dn];await api.value.delete(`/api/datasets/${encodeURIComponent(dn)}`);await refreshDatasets();await loadTrainingRecords();showMsg('数据集及关联资源已删除')}catch(e){showMsg(e.response?.data?.message||(e.response?.status===404?'数据集不存在':'删除失败'),'error')}finally{deleteConfirmVisible.value=false}}

const handleCleanup=()=>{cleanupConfirmVisible.value=true}
const confirmCleanup=async()=>{cleanupLoading.value=true;try{await api.value.post('/api/cleanup');await refreshDatasets();await loadTrainingRecords();await loadOperationLogs();showMsg('系统已重置');stopAllWs();Object.keys(preprocessLogs.value).forEach(k=>delete preprocessLogs.value[k]);Object.keys(trainLogs.value).forEach(k=>delete trainLogs.value[k]);Object.keys(testLogs.value).forEach(k=>delete testLogs.value[k])}catch(e){showMsg(e.response?.data?.message||'清理失败','error')}finally{cleanupLoading.value=false;cleanupConfirmVisible.value=false}}

const handleDefaultEpochsChange=async()=>{try{await api.value.post('/api/scheduler/default-epochs',{epochs:defaultEpochs.value});savedDefaultEpochs.value=defaultEpochs.value;showMsg(`默认Epochs已设为 ${defaultEpochs.value}`)}catch(e){showMsg('更新失败','error')}}
const handleDefaultImgszChange=async()=>{try{await api.value.post('/api/scheduler/default-imgsz',{imgsz:defaultImgsz.value});savedDefaultImgsz.value=defaultImgsz.value;showMsg(`默认Imgsz已设为 ${defaultImgsz.value}`)}catch(e){showMsg('更新失败','error')}}

const handleAddUser=async()=>{if(currentUser.role==='ADMIN')newUserForm.role='USER';try{await api.value.post('/api/auth/register',newUserForm);showAddUserDialog.value=false;newUserForm.username='';newUserForm.password='';newUserForm.role='USER';loadUsers();showMsg('用户创建成功')}catch(e){showMsg(e.response?.data?.message||'创建失败','error')}}
const handleDeleteUser=(user)=>{userToDelete.value=user;deleteUserConfirmVisible.value=true}
const confirmDeleteUser=async()=>{try{await api.value.delete(`/api/users/${userToDelete.value.id}`);deleteUserConfirmVisible.value=false;loadUsers();showMsg('用户已删除')}catch(e){showMsg(e.response?.data?.message||'删除失败','error')}}
const handleChangePassword=async()=>{if(!changePasswordForm.oldPassword||!changePasswordForm.newPassword){showMsg('请填写完整','warning');return}if(changePasswordForm.newPassword!==changePasswordForm.confirmPassword){showMsg('两次密码不一致','warning');return}if(changePasswordForm.newPassword.length<4){showMsg('新密码至少4位','warning');return}try{await api.value.put('/api/users/change-password',{oldPassword:changePasswordForm.oldPassword,newPassword:changePasswordForm.newPassword});showMsg('密码修改成功');showChangePasswordDialog.value=false;changePasswordForm.oldPassword='';changePasswordForm.newPassword='';changePasswordForm.confirmPassword=''}catch(e){showMsg(e.response?.data?.message||'修改失败','error')}}
const handleSizeChange=v=>{pageSize.value=v;currentPage.value=1}
const handleCurrentChange=v=>{currentPage.value=v}
const getStatusClass=s=>{if(s==='RUNNING')return'warning';if(s==='QUEUED')return'primary';if(s==='COMPLETED')return'success';if(s==='FAILED')return'danger';return'info'}
const getStatusText=(s,t)=>{const p=t==='train'?'训练':'测试';if(s==='RUNNING')return p+'中';if(s==='QUEUED')return'排队中';if(s==='COMPLETED')return'已完成';if(s==='FAILED')return'失败';return t==='train'?'未训练':'未测试'}
const formatMemory=v=>{if(!v)return'-';const s=String(v).trim();let bytes=0;const m=s.match(/^(\d+(?:\.\d+)?)(Ki|Mi|Gi|Ti|Pi|Ei|K|M|G|T|P|E)?$/i);if(m){const num=parseFloat(m[1]);const unit=(m[2]||'').toUpperCase();const units={KI:1024,MI:1024*1024,GI:1024*1024*1024,TI:1024*1024*1024*1024,PI:1024*1024*1024*1024*1024,EI:1024*1024*1024*1024*1024*1024,K:1000,M:1000*1000,G:1000*1000*1000,T:1000*1000*1000*1000,P:1000*1000*1000*1000*1000,E:1000*1000*1000*1000*1000*1000};bytes=num*(units[unit]||1)}else{const n=parseInt(s);if(isNaN(n))return v;bytes=n}if(bytes>=1073741824)return(bytes/1073741824).toFixed(1)+'GB';if(bytes>=1048576)return(bytes/1048576).toFixed(0)+'MB';if(bytes>=1024)return(bytes/1024).toFixed(0)+'KB';return bytes+'B'}
const formatMemoryMB=v=>{if(!v)return 0;const s=String(v).trim();const m=s.match(/^(\d+(?:\.\d+)?)(Ki|Mi|Gi|Ti|K|M|G)?$/i);if(m){const num=parseFloat(m[1]);const unit=(m[2]||'').toUpperCase();if(unit==='KI')return num/1024;if(unit==='MI')return num;if(unit==='GI')return num*1024;if(unit==='K')return num/1000;if(unit==='M')return num;if(unit==='G')return num*1000;return num}return parseFloat(s)||0}
const formatStartTime=v=>{if(!v)return'-';try{const d=new Date(v);const now=new Date();const diff=Math.floor((now-d)/1000);if(diff<60)return diff+'秒前';if(diff<3600)return Math.floor(diff/60)+'分钟前';if(diff<86400)return Math.floor(diff/3600)+'小时前';return Math.floor(diff/86400)+'天前'}catch(e){return v}}
const getServiceTypeLabel=t=>({nfs:'NFS存储',mysql:'MySQL数据库',backend:'后端服务',frontend:'前端服务',ingress:'Ingress网关',etcd:'etcd',dns:'CoreDNS','kube-apiserver':'API Server','kube-controller':'Controller','kube-scheduler':'Scheduler','kube-proxy':'kube-proxy',network:'网络插件','k8s-system':'K8s系统',other:'其他'}[t]||t)
const getServiceTypeColor=t=>({nfs:'warning',mysql:'danger',backend:'primary',frontend:'success',ingress:'',etcd:'info',dns:'info','kube-apiserver':'danger','kube-controller':'warning','kube-scheduler':'warning','kube-proxy':'info',network:'info','k8s-system':'info',other:'info'}[t]||'info')
const getCpuCoreCount=row=>{const v=row.cpuAllocatable||row.cpuCapacity||'4';const n=parseInt(v);return isNaN(n)?4:n}

const viewRunsResult=async(recordName,type)=>{runsResultCurrentRecord.value=recordName;runsResultCurrentType.value=type;runsResultTitle.value=`${recordName} - ${type==='train'?'训练':'测试'}结果`;runsResultVisible.value=true;runsResultLoading.value=true;runsResultData.value={};try{const r=await api.value.get(`/api/nodes/runs/${recordName}/${type}`);runsResultData.value=r.data}catch(e){runsResultData.value={exists:false}}finally{runsResultLoading.value=false}}
const loadRunsFile=async(recordName,type,path)=>{try{const r=await api.value.get(`/api/nodes/runs/${recordName}/${type}/file`,{params:{path}});if(r.data.content){runsFileTitle.value=r.data.fileName||path;runsFileContent.value=r.data.content;runsFileVisible.value=true}else{showMsg(r.data.error||'文件读取失败','error')}}catch(e){showMsg('文件读取失败','error')}}

const switchToModels=()=>{activePage.value='models';loadModelList()}
const loadModelList=async()=>{try{const r=await api.value.get('/api/models');modelList.value=r.data}catch(e){}}
const showPredictDialog=m=>{predictForm.modelId=m.id;predictForm.modelName=m.modelName;predictForm.dataName='';predictDialogVisible.value=true}
const handlePredict=async()=>{if(!predictForm.dataName){showMsg('请选择目标数据集','warning');return}predictLoading.value=true;try{const r=await api.value.post(`/api/models/${predictForm.modelId}/predict`,{dataName:predictForm.dataName});if(r.data.status==='success'){showMsg('推理任务已提交');predictDialogVisible.value=false;predictResultModelId.value=predictForm.modelId;predictResultDataName.value=predictForm.dataName;predictResultVisible.value=true;predictResultLoading.value=true;predictResultImages.value=[];pollPredictResults()}}catch(e){showMsg(e.response?.data?.message||'推理失败','error')}finally{predictLoading.value=false}}
const pollPredictResults=async()=>{const iv=setInterval(async()=>{try{const r=await api.value.get(`/api/models/${predictResultModelId.value}/predict-results`,{params:{dataName:predictResultDataName.value}});if(r.data.exists){predictResultImages.value=r.data.images||[];predictResultLoading.value=false;clearInterval(iv)}}catch(e){}},3000);setTimeout(()=>{clearInterval(iv);predictResultLoading.value=false},60000)}
const getPredictImageUrl=name=>`/api/models/${predictResultModelId.value}/predict-image?dataName=${encodeURIComponent(predictResultDataName.value)}&imageName=${encodeURIComponent(name)}&token=${token.value}`
const openPredictImage=name=>{window.open(getPredictImageUrl(name),'_blank')}
const handleDeleteModel=async m=>{try{await api.value.delete(`/api/models/${m.id}`);showMsg('模型已删除');loadModelList()}catch(e){showMsg(e.response?.data?.message||'删除失败','error')}}

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
.login-card{background:rgba(255,255,255,.95);border-radius:16px;padding:40px;width:min(380px,90vw);box-shadow:0 20px 60px rgba(0,0,0,.3);z-index:1}
.login-header{text-align:center;margin-bottom:30px}.login-header h2{margin:12px 0 0;color:#303133}
.login-error{color:#f56c6c;text-align:center;margin-top:12px;font-size:13px}

.app-layout{display:flex;min-height:100vh;background:#f0f2f5;position:relative}
.bg-decoration{position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:0;overflow:hidden}
.bg-circle{position:absolute;border-radius:50%;opacity:.06}.bg-circle-1{width:min(600px,50vw);height:min(600px,50vw);background:#409eff;top:-200px;right:-100px}.bg-circle-2{width:min(400px,35vw);height:min(400px,35vw);background:#67c23a;bottom:-100px;left:-80px}.bg-circle-3{width:min(300px,25vw);height:min(300px,25vw);background:#e6a23c;top:50%;left:50%;transform:translate(-50%,-50%)}

.sidebar{width:clamp(160px,15vw,200px);background:linear-gradient(180deg,#1a1a2e,#16213e);color:#fff;display:flex;flex-direction:column;position:fixed;top:0;left:0;bottom:0;z-index:10;box-shadow:2px 0 12px rgba(0,0,0,.15)}
.sidebar-logo{display:flex;align-items:center;gap:10px;padding:20px 18px;border-bottom:1px solid rgba(255,255,255,.1)}
.logo-icon{width:36px;height:36px;background:linear-gradient(135deg,#409eff,#67c23a);border-radius:8px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px;font-weight:800;flex-shrink:0}
.logo-text{font-size:clamp(14px,1.5vw,18px);font-weight:700;letter-spacing:1px}
.sidebar-nav{flex:1;padding:12px 0}
.nav-item{display:flex;align-items:center;gap:10px;padding:12px 20px;cursor:pointer;transition:all .2s;color:rgba(255,255,255,.65);font-size:14px;border-left:3px solid transparent}
.nav-item:hover{background:rgba(255,255,255,.08);color:#fff}
.nav-item.active{background:rgba(64,158,255,.15);color:#409eff;border-left-color:#409eff}
.sidebar-footer{padding:14px 10px;border-top:1px solid rgba(255,255,255,.1)}
.user-badge{display:flex;align-items:center;gap:6px;margin-bottom:8px}.user-name{font-size:13px;color:rgba(255,255,255,.8)}
.footer-actions{display:flex;flex-direction:column;gap:4px;align-items:flex-start}

.main-content{margin-left:clamp(160px,15vw,200px);flex:1;padding:clamp(12px,2vw,28px);position:relative;z-index:1;min-height:100vh;box-sizing:border-box}
.page-header{margin-bottom:18px}.page-header h2{margin:0;font-size:clamp(16px,2vw,20px);color:#303133;font-weight:700}

.top-section{display:flex;gap:14px;margin-bottom:16px;flex-wrap:wrap}
.top-card{flex:1;min-width:min(300px,100%)}
.upload-card{flex:3;min-width:min(400px,100%)}
.config-card{flex:2;min-width:min(250px,100%)}
.card-title{font-size:15px;font-weight:600;color:#303133}

.upload-area{border:2px dashed #c0c4cc;border-radius:10px;padding:12px 20px;text-align:center;transition:all .3s;cursor:pointer}
.upload-area:hover{border-color:#409eff;background:rgba(64,158,255,.04)}
.upload-area-active{border-color:#409eff;background:rgba(64,158,255,.08)}
.upload-icon{color:#409eff;margin-bottom:4px}
.upload-hint{font-size:14px;color:#606266;margin:4px 0}

.config-row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.cfg-item{display:flex;align-items:center;gap:5px}.cfg-label{font-size:12px;color:#606266;white-space:nowrap}.cfg-value{font-size:18px;font-weight:700;color:#409eff}.cfg-hint{font-size:11px;color:#909399}

.datasets-section{margin-bottom:16px}.dataset-card{margin-bottom:12px}
.dataset-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;padding-bottom:8px;border-bottom:1px solid #ebeef5;flex-wrap:wrap;gap:8px}
.dataset-info{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.dataset-name{font-size:15px;font-weight:700;color:#303133}
.header-actions{display:flex;align-items:center;gap:6px;flex-shrink:0;flex-wrap:wrap}

.records-table{width:100%;border-collapse:collapse;font-size:13px;table-layout:auto}
.records-table th{background:#f5f7fa;padding:7px 4px;text-align:center;font-weight:600;color:#606266;border:1px solid #ebeef5;white-space:nowrap}
.records-table td{padding:6px 4px;text-align:center;vertical-align:middle;border:1px solid #ebeef5}
.col-epoch{width:80px}.col-imgsz{width:90px}.col-node{width:200px}.col-tstatus{width:140px}.col-estatus{width:100px}
.col-nname{width:120px}.col-nip{width:130px}.col-nrole{width:80px}.col-nstatus{width:70px}.col-nsched{width:65px}.col-ncpu{width:55px}.col-nmem{width:85px}.col-ngpu{width:55px}.col-nconc{width:170px}.col-ntasks{width:80px}.col-nremain{width:65px}.col-nact{width:190px}
.col-luser{width:100px}.col-laction{width:140px}.col-ltarget{width:150px}.col-ldetail{width:200px}.col-ltime{width:170px}
.col-uuser{width:130px}.col-urole{width:100px}.col-ucreator{width:100px}.col-utime{width:170px}.col-uact{width:120px}
.param-fixed{font-weight:600;color:#303133}.status-cell{display:flex;flex-direction:column;align-items:center;gap:2px}
.record-actions{display:flex;gap:3px;justify-content:center;flex-wrap:wrap}
.pagination-container{display:flex;justify-content:center;margin-top:16px}

.logs-section{margin-bottom:16px}.log-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
.log-item{border:1px solid #dcdfe6;border-radius:8px;overflow:hidden}
.log-header{display:flex;justify-content:space-between;align-items:center;padding:8px 14px;background:#f5f7fa;border-bottom:1px solid #dcdfe6}
.log-container{max-height:350px;overflow-y:auto;padding:10px 14px;background:#fff}
.log-content{white-space:pre-wrap;font-family:'Cascadia Code','Fira Code','Consolas',monospace;font-size:12px;color:#303133;margin:0;line-height:1.6}
.train-log-structured{white-space:normal;font-family:'Cascadia Code','Fira Code','Consolas',monospace;font-size:12px;color:#303133;line-height:1.6}
.tl-status{padding:8px 0;color:#909399;font-size:13px}
.tl-header{white-space:pre-wrap;font-family:'Cascadia Code','Fira Code','Consolas',monospace;font-size:12px;color:#606266;margin:0 0 8px 0;padding:8px 10px;background:#fafafa;border-radius:4px;line-height:1.5;border-left:3px solid #dcdfe6}
.tl-progress{padding:8px 12px;background:#f0f9eb;border-radius:4px;margin-bottom:8px;font-size:13px;border-left:3px solid #67c23a}
.tl-pct{font-weight:bold;color:#409eff;font-size:14px}
.tl-table{width:100%;border-collapse:collapse;margin-top:4px;font-size:12px}
.tl-table th{background:#f5f7fa;padding:6px 8px;text-align:left;border-bottom:2px solid #dcdfe6;color:#606266;font-weight:600}
.tl-table td{padding:5px 8px;border-bottom:1px solid #ebeef5}
.tl-table tr.tl-best{background:#f0f9eb;font-weight:bold}
.tl-table tr.tl-best td{color:#67c23a;border-bottom:2px solid #67c23a}

.filter-card :deep(.el-card__body){padding:14px 18px}
.filter-row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.filter-item{display:flex;align-items:center;gap:6px}.filter-label{font-size:13px;color:#606266;white-space:nowrap}

.save-path-row{display:flex;gap:8px;width:100%}.save-path-row .el-input{flex:1}
.fixed-table{width:100%}
.responsive-table{width:100%;table-layout:auto}

.cluster-overview{display:flex;gap:12px;flex-wrap:wrap}
.overview-card{flex:1;min-width:min(120px,45%);text-align:center;padding:8px 0}
.overview-value{font-size:clamp(22px,3vw,28px);font-weight:800;color:#303133}
.overview-label{font-size:12px;color:#909399;margin-top:4px}
.overview-ready .overview-value{color:#67c23a}
.overview-gpu .overview-value{color:#e6a23c}
.overview-concurrent .overview-value{color:#409eff}
.overview-remaining .overview-value{color:#67c23a}

.node-concurrent{display:flex;align-items:center;gap:4px;justify-content:center}

.node-resource-grid{display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:14px}
.node-res-item{background:#f5f7fa;border-radius:8px;padding:12px 14px}
.node-res-label{font-size:12px;color:#909399;margin-bottom:6px;font-weight:600}
.node-res-bar{margin-bottom:6px}
.node-res-detail{font-size:12px;color:#606266}
.node-res-score{font-size:28px;font-weight:800;color:#409eff;margin-bottom:4px}

.est-res-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.est-res-item{background:#f5f7fa;border-radius:8px;padding:10px 14px}
.est-res-label{font-size:12px;color:#909399;margin-bottom:6px;font-weight:600}
.est-res-bar{margin-bottom:4px}
.est-res-sub{font-size:12px;color:#606266}

.system-summary{display:flex;gap:12px;flex-wrap:wrap}

@media(max-width:900px){.node-resource-grid{grid-template-columns:1fr 1fr}}

:deep(.el-card){border-radius:12px;border:none}:deep(.el-card__header){padding:12px 18px;border-bottom:1px solid #ebeef5}:deep(.el-card__body){padding:14px 18px}
:deep(.el-table){font-size:13px}:deep(.el-table th.el-table__cell){background-color:#f5f7fa!important;text-align:center!important}:deep(.el-dialog){border-radius:12px}

@media(max-width:900px){.top-section{flex-direction:column}.upload-card,.config-card{flex:auto;min-width:100%}.cluster-overview .overview-card{min-width:45%}.filter-row{gap:8px}.log-grid{grid-template-columns:1fr}}
@media(max-width:700px){.sidebar{width:60px}.sidebar-logo .logo-text,.nav-item span,.btn-label{display:none}.sidebar-logo{justify-content:center;padding:16px 8px}.nav-item{justify-content:center;padding:14px 8px}.sidebar-footer{padding:8px 4px;overflow:hidden}.user-badge{justify-content:center}.user-badge .user-name{display:none}.footer-actions .el-button{padding:4px 6px;min-width:40px;display:inline-flex;justify-content:center}.main-content{margin-left:60px;padding:10px}.log-grid{grid-template-columns:1fr}.header-actions{flex-wrap:wrap}.records-table{font-size:12px}.records-table td,.records-table th{padding:4px 2px}}
</style>
