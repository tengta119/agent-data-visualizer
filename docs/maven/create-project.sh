mvn archetype:generate
-DarchetypeGroupId=cn.bugstack.ai
-DarchetypeArtifactId=ai-agent-scaffold-lite-archetype
-DarchetypeVersion=1.0
-X -DarchetypeCatalog=local

mvn archetype:generate -X -DarchetypeCatalog=local
