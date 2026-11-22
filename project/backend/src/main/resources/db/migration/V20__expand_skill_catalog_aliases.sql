-- V20: 扩充技能目录并增加别名机制。
-- 背景：文字版简历解析漏掉了明确写出的技能（React、MyBatis、LangChain、LangGraph、Qdrant 等），
-- 当时目录只有约 20 项。本迁移补齐常见前端、后端、Agent、RAG 和向量数据库技能，
-- 并为 skill_catalog 增加 aliases 列（分号分隔），用于解析时的目录别名匹配。
-- 沿用已有编码风格：大写下划线；INSERT IGNORE 兼容已有数据（含演示数据插入的 JAVA 等）。

ALTER TABLE skill_catalog ADD COLUMN aliases VARCHAR(500) NULL;

INSERT IGNORE INTO skill_catalog (code, name, enabled, aliases) VALUES
  -- 基础项兜底（与演示数据一致，已存在时跳过）
  ('JAVA', 'Java', 1, NULL),
  ('SPRING_BOOT', 'Spring Boot', 1, 'SpringBoot;Spring Framework Boot'),
  ('MYSQL', 'MySQL', 1, 'Mysql;my sql'),
  ('REDIS', 'Redis', 1, NULL),
  ('SYSTEM_DESIGN', '系统设计', 1, '系统架构'),
  ('COMMUNICATION', '沟通表达', 1, '沟通能力;表达能力'),
  -- 前端
  ('REACT', 'React', 1, 'React.js;ReactJS;React 18'),
  ('NODE_JS', 'Node.js', 1, 'Node;Nodejs;NodeJS'),
  ('HTML', 'HTML', 1, 'HTML5;超文本标记语言'),
  ('CSS', 'CSS', 1, 'CSS3;层叠样式表'),
  ('WEBPACK', 'Webpack', 1, NULL),
  ('VITE', 'Vite', 1, NULL),
  -- 后端
  ('MYBATIS', 'MyBatis', 1, 'Mybatis;MyBatis-Plus;Mybatis Plus'),
  ('SPRING', 'Spring', 1, 'Spring Framework;Spring框架'),
  ('MAVEN', 'Maven', 1, 'Apache Maven'),
  ('MONGODB', 'MongoDB', 1, 'Mongo;mongodb'),
  ('POSTGRESQL', 'PostgreSQL', 1, 'Postgres;PgSQL;postgresql'),
  ('SQL', 'SQL', 1, 'SQL 语言'),
  ('ELASTICSEARCH', 'Elasticsearch', 1, 'ES 检索;elastic search'),
  -- Agent 与 RAG
  ('LANGCHAIN', 'LangChain', 1, 'langchain;Lang Chain'),
  ('LANGGRAPH', 'LangGraph', 1, 'langgraph;Lang Graph'),
  ('LANGCHAIN4J', 'LangChain4j', 1, 'langchain4j;LangChain4J'),
  ('SPRING_AI', 'Spring AI', 1, 'SpringAI;Spring AI 框架'),
  ('REACT_PATTERN', 'ReAct', 1, 'ReAct 模式;ReAct 范式'),
  ('RAG', 'RAG', 1, '检索增强生成;RAG 检索增强;Retrieval-Augmented Generation'),
  ('PROMPT_ENGINEERING', 'Prompt Engineering', 1, '提示词工程;提示工程'),
  ('AGENT_DEVELOPMENT', 'Agent 开发', 1, '智能体开发;AI Agent 开发;LLM Agent'),
  -- 向量数据库
  ('QDRANT', 'Qdrant', 1, 'qdrant'),
  ('MILVUS', 'Milvus', 1, 'milvus'),
  ('FAISS', 'Faiss', 1, 'FAISS;Meta Faiss'),
  ('CHROMA', 'Chroma', 1, 'ChromaDB;Chroma DB'),
  ('PINECONE', 'Pinecone', 1, 'pinecone'),
  ('VECTOR_DATABASE', '向量数据库', 1, '向量库;Vector Database;VectorDB'),
  -- 数据与机器学习
  ('PYTORCH', 'PyTorch', 1, 'pytorch;Torch'),
  ('TENSORFLOW', 'TensorFlow', 1, 'tensorflow;TF2'),
  ('SKLEARN', 'scikit-learn', 1, 'sklearn;Scikit Learn'),
  ('PANDAS', 'Pandas', 1, 'pandas'),
  ('NUMPY', 'NumPy', 1, 'numpy'),
  -- 中间件与工程
  ('KAFKA', 'Kafka', 1, 'Apache Kafka'),
  ('RABBITMQ', 'RabbitMQ', 1, 'rabbit mq'),
  ('GIT', 'Git', 1, 'git 版本控制;Git 版本管理'),
  ('LINUX', 'Linux', 1, 'linux 系统;Linux 系统'),
  ('LLM', '大语言模型', 1, 'Large Language Model;LLM 模型'),
  ('NLP', '自然语言处理', 1, 'Natural Language Processing;NLP 技术'),
  ('COMPUTER_VISION', '计算机视觉', 1, 'Computer Vision;CV 视觉');
